package com.genco.tercuman

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var whisper: WhisperEngine
    private lateinit var translator: TranslationEngine
    private lateinit var voice: NeuralVoiceEngine
    private lateinit var micChunker: MicrophoneChunker

    private lateinit var status: TextView
    private lateinit var sourceText: TextView
    private lateinit var translatedText: TextView
    private lateinit var modelButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var voiceSpinner: Spinner
    private lateinit var speakSwitch: Switch

    private enum class CaptureMode { NONE, MIC, PHONE }

    // Canlı çeviride eski parçaların kuyrukta birikmesine izin vermiyoruz.
    // İşlem yetişemezse en eski parça atılır; böylece çeviri geriden gelmez.
    private val chunkQueue = Channel<File>(capacity = 1)
    private data class SpeechTask(val text: String, val profile: NeuralVoiceEngine.VoiceProfile)
    private val ttsQueue = Channel<SpeechTask>(capacity = 1)
    @Volatile private var captureMode = CaptureMode.NONE
    private var micOn = false
    private var phoneOn = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStatus() }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startPlaybackService(result.resultCode, result.data!!)
        else status.text = "Telefon sesi izni verilmedi."
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackCaptureService.ACTION_CHUNK -> intent.getStringExtra(PlaybackCaptureService.EXTRA_PATH)?.let {
                    enqueueChunk(File(it), CaptureMode.PHONE)
                }
                PlaybackCaptureService.ACTION_STATUS -> intent.getStringExtra(PlaybackCaptureService.EXTRA_STATUS)?.let {
                    if (captureMode == CaptureMode.PHONE) status.text = it
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        whisper = WhisperEngine(this, modelManager)
        translator = TranslationEngine()
        voice = NeuralVoiceEngine(modelManager)
        micChunker = MicrophoneChunker(this) { enqueueChunk(it, CaptureMode.MIC) }
        setContentView(buildUi())
        requestBasePermissions()
        registerPlaybackReceiver()
        // Önceki Activity örneğinden kalmış bir playback capture servisi varsa
        // yeni oturum bunu otomatik olarak kullanmamalı. Kullanıcı butona basana kadar NONE.
        stopPlaybackServiceSilently()
        lifecycleScope.launch { processQueue() }
        lifecycleScope.launch { processTtsQueue() }
        updateStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = t; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(12, 27, 51)); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        col.addView(text("TERCÜMAN", 28f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(text("Canlı çeviri • yerel AI • sıfır API ücreti", 13f, Color.rgb(170,184,197)))
        status = text("Hazırlanıyor…", 14f, Color.rgb(85,214,190)); col.addView(status)

        modelButton = MaterialButton(this).apply { text = "AI MODELLERİNİ HAZIRLA"; setOnClickListener { downloadModels() } }
        col.addView(modelButton, lp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = MaterialButton(this).apply { text = "🎤 DIŞ SES"; setOnClickListener { toggleMic() } }
        phoneButton = MaterialButton(this).apply { text = "📱 TELEFON SESİ"; setOnClickListener { togglePhone() } }
        row.addView(micButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(phoneButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row, lp())

        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voiceRow.addView(text("Ses:", 14f), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voice.profiles.map { it.label })
            setSelection(0)
        }
        voiceRow.addView(voiceSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speakSwitch = Switch(this).apply {
            text = "Sesli"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (!checked) {
                    clearTtsQueue()
                }
            }
        }
        voiceRow.addView(speakSwitch)
        col.addView(voiceRow, lp())

        val preview = MaterialButton(this).apply {
            text = "▶ SESİ ÖNİZLE"
            setOnClickListener {
                lifecycleScope.launch {
                    runCatching {
                        if (!modelManager.supertonicReady()) error("Önce AI modellerini hazırla.")
                        status.text = "Doğal ses oluşturuluyor…"
                        voice.speakTurkish("Merhaba. Ben Tercüman. Yabancı konuşmaları anında Türkçeye çevirmeye hazırım.", selectedVoice())
                        status.text = "Ses önizlemesi oynatılıyor."
                    }.onFailure { status.text = it.message ?: "Ses hatası" }
                }
            }
        }
        col.addView(preview, lp())

        col.addView(card("ORİJİNAL", "Konuşma burada görünecek.").also { sourceText = it.getChildAt(0) as TextView }, lp())
        col.addView(card("TÜRKÇE", "Çeviri burada görünecek.").also { translatedText = it.getChildAt(0) as TextView }, lp())

        col.addView(text("Bölünmüş ekran için tasarlandı: başka uygulamayı üstte/yan tarafta açıp TERCÜMAN'ı diğer yarıda kullanabilirsin.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Not: Telefon içi ses yakalama Android'in kaynak uygulama politikasına bağlıdır. Sessiz akışta uygulama bunu açıkça bildirir.", 12f, Color.rgb(170,184,197)))
        root.addView(col)
        return root
    }

    private fun card(title: String, initial: String): MaterialCardView {
        val tv = TextView(this).apply {
            text = "$title\n\n$initial"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(16), dp(16), dp(16), dp(16)); minHeight = dp(130)
        }
        return MaterialCardView(this).apply {
            radius = dp(14).toFloat(); setCardBackgroundColor(Color.rgb(20,42,74)); strokeWidth = 1; strokeColor = Color.rgb(85,214,190); addView(tv)
        }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }

    private fun selectedVoice() = voice.profiles[voiceSpinner.selectedItemPosition.coerceIn(0, voice.profiles.lastIndex)]

    private fun requestBasePermissions() {
        val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(req.toTypedArray())
    }

    private fun downloadModels() {
        lifecycleScope.launch {
            modelButton.isEnabled = false
            try {
                if (!modelManager.whisperReady()) {
                    status.text = "Whisper indiriliyor…"
                    modelManager.ensureWhisper { p -> runOnUiThread { status.text = "Whisper: %$p" } }
                }
                if (!modelManager.supertonicReady()) {
                    status.text = "Doğal Türkçe ses modeli indiriliyor…"
                    modelManager.ensureSupertonic { p -> runOnUiThread { status.text = "Doğal ses: %$p" } }
                }
                status.text = "Hazır ✓  Çeviri ve doğal ses cihazda çalışacak."
            } catch (e: Exception) {
                status.text = "Model hazırlama hatası: ${e.message}"
            } finally {
                modelButton.isEnabled = true
            }
        }
    }

    private fun toggleMic() {
        if (!modelManager.whisperReady()) { status.text = "Önce AI modellerini hazırla."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestBasePermissions(); return
        }
        if (micOn) {
            micChunker.stop()
            micOn = false
            if (captureMode == CaptureMode.MIC) captureMode = CaptureMode.NONE
            clearChunkQueue()
            clearTtsQueue()
            micButton.text = "🎤 DIŞ SES"
            status.text = "Mikrofon durduruldu."
        } else {
            stopPhoneIfNeeded()
            clearChunkQueue()
            clearTtsQueue()
            captureMode = CaptureMode.MIC
            try {
                micChunker.start()
                micOn = true
                micButton.text = "⏹ DIŞ SESİ DURDUR"
                status.text = "Dış ses dinleniyor…"
            } catch (e: Exception) {
                captureMode = CaptureMode.NONE
                status.text = e.message ?: "Mikrofon başlatılamadı."
            }
        }
    }

    private fun togglePhone() {
        if (!modelManager.whisperReady()) { status.text = "Önce AI modellerini hazırla."; return }
        if (phoneOn) {
            stopPhoneIfNeeded()
        } else {
            // Yeni MediaProjection oturumu açmadan önce olası eski servisi tamamen kapat.
            stopPlaybackServiceSilently()
            micChunker.stop()
            micOn = false
            captureMode = CaptureMode.NONE
            clearChunkQueue()
            clearTtsQueue()
            micButton.text = "🎤 DIŞ SES"
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun startPlaybackService(code: Int, data: Intent) {
        clearChunkQueue()
        captureMode = CaptureMode.PHONE
        val i = Intent(this, PlaybackCaptureService::class.java)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, code)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        phoneOn = true
        phoneButton.text = "⏹ TELEFON SESİNİ DURDUR"
        status.text = "Telefon medya sesi dinleniyor…"
    }

    private fun stopPhoneIfNeeded() {
        val wasPhone = phoneOn
        captureMode = CaptureMode.NONE
        clearChunkQueue()
        clearTtsQueue()
        // Servisi doğrudan durdur: eski MediaProjection oturumunun yeniden canlanmasını engeller.
        stopPlaybackServiceSilently()
        phoneOn = false
        phoneButton.text = "📱 TELEFON SESİ"
        if (wasPhone) status.text = "Telefon sesi durduruldu."
    }

    private fun stopPlaybackServiceSilently() {
        runCatching {
            stopService(Intent(this, PlaybackCaptureService::class.java))
        }
    }

    private fun enqueueTts(text: String) {
        if (text.isBlank() || captureMode == CaptureMode.NONE || !speakSwitch.isChecked) return
        // TTS de canlı kuyruk mantığı kullanır: yeni çeviri gelince eski ses kesilir.
        voice.stop()
        val task = SpeechTask(text.take(260), selectedVoice())
        val old = ttsQueue.tryReceive().getOrNull()
        if (old != null) { /* eski metin artık oynatılmayacak */ }
        if (ttsQueue.trySend(task).isFailure) {
            // Tek yuvalı kuyruk doluysa son bir kez eski öğeyi değiştir.
            ttsQueue.tryReceive().getOrNull()
            ttsQueue.trySend(task)
        }
    }

    private fun enqueueChunk(file: File, source: CaptureMode) {
        if (!file.exists() || captureMode != source) {
            file.delete()
            return
        }
        if (chunkQueue.trySend(file).isSuccess) return

        // İşlemci yetişemiyorsa bekleyen eski parçayı at, en yeni parçayı tut.
        val stale = chunkQueue.tryReceive().getOrNull()
        stale?.delete()
        if (chunkQueue.trySend(file).isFailure) file.delete()
    }

    private fun clearChunkQueue() {
        while (true) {
            val stale = chunkQueue.tryReceive().getOrNull() ?: break
            stale.delete()
        }
    }

    private fun clearTtsQueue() {
        while (ttsQueue.tryReceive().getOrNull() != null) { }
        voice.stop()
    }

    private suspend fun processTtsQueue() {
        for (task in ttsQueue) {
            if (captureMode == CaptureMode.NONE || !speakSwitch.isChecked) continue
            runCatching {
                voice.speakTurkish(task.text, task.profile)
            }.onFailure {
                if (captureMode != CaptureMode.NONE) status.text = "Ses hatası: ${it.message ?: "bilinmeyen hata"}"
            }
        }
    }

    private suspend fun processQueue() {
        for (wav in chunkQueue) {
            try {
                // Kaynak kapatıldıysa veya başka moda geçildiyse eski parçayı kesinlikle işleme.
                if (captureMode == CaptureMode.NONE) {
                    wav.delete()
                    continue
                }
                status.text = "Dinledim, çözüyorum…"
                val original = withContext(Dispatchers.Default) { whisper.transcribe(wav) }
                wav.delete()
                if (captureMode == CaptureMode.NONE || original.length < 2) continue
                sourceText.text = "ORİJİNAL\n\n$original"
                status.text = "Türkçeye çevriliyor…"
                val (tr, lang) = translator.toTurkish(original)
                if (captureMode == CaptureMode.NONE) continue
                translatedText.text = "TÜRKÇE  •  ${lang.uppercase()}\n\n$tr"
                status.text = "Canlı çeviri aktif ✓"
                if (speakSwitch.isChecked && modelManager.supertonicReady() && captureMode != CaptureMode.NONE) {
                    // STT + çeviri TTS'yi beklemez. TTS ayrı işçide çalışır ve
                    // yeni çeviri geldiğinde eski ses kesilerek en güncel metin oynatılır.
                    enqueueTts(tr)
                }
            } catch (e: Exception) {
                wav.delete()
                if (captureMode != CaptureMode.NONE) status.text = "Çeviri hatası: ${e.message}"
            }
        }
    }

    private fun registerPlaybackReceiver() {
        val filter = IntentFilter().apply {
            addAction(PlaybackCaptureService.ACTION_CHUNK)
            addAction(PlaybackCaptureService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playbackReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(playbackReceiver, filter)
    }

    private fun updateStatus() {
        status.text = when {
            !modelManager.whisperReady() || !modelManager.supertonicReady() -> "İlk kullanım: AI MODELLERİNİ HAZIRLA'ya dokun."
            else -> "Hazır ✓"
        }
    }

    override fun onDestroy() {
        captureMode = CaptureMode.NONE
        micChunker.stop()
        stopPlaybackServiceSilently()
        clearChunkQueue()
        clearTtsQueue()
        voice.release()
        whisper.release()
        translator.close()
        runCatching { unregisterReceiver(playbackReceiver) }
        super.onDestroy()
    }
}
