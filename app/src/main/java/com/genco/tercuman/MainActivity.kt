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
import android.view.View
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
    private lateinit var modelProgressText: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var sourceText: TextView
    private lateinit var translatedText: TextView
    private lateinit var modelButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var voiceSpinner: Spinner
    private lateinit var toneSpinner: Spinner
    private lateinit var speakSwitch: Switch

    private enum class CaptureMode { NONE, MIC, PHONE }
    private val chunkQueue = Channel<File>(capacity = 1)
    private data class SpeechTask(
        val text: String,
        val profile: NeuralVoiceEngine.VoiceProfile,
        val tone: NeuralVoiceEngine.ToneProfile
    )
    private val ttsQueue = Channel<SpeechTask>(capacity = 1)
    @Volatile private var captureMode = CaptureMode.NONE
    private var micOn = false
    private var phoneOn = false
    private var preparingModels = false
    private var lastTranslationAt = 0L

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
        stopPlaybackServiceSilently()
        lifecycleScope.launch { processQueue() }
        lifecycleScope.launch { processTtsQueue() }
        updateModelIndicator()
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

        modelButton = MaterialButton(this).apply {
            text = "AI MODELLERİNİ HAZIRLA"
            setOnClickListener { downloadModels() }
        }
        col.addView(modelButton, lp())

        modelProgressText = text("AI durumu kontrol ediliyor…", 11f, Color.rgb(170,184,197))
        col.addView(modelProgressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        col.addView(modelProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(2)
        })

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
            text = "Sesli çıktı"; setTextColor(Color.WHITE); isChecked = false
            setOnCheckedChangeListener { _, checked -> if (!checked) clearTtsQueue() }
        }
        voiceRow.addView(speakSwitch)
        col.addView(voiceRow, lp())

        val toneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toneRow.addView(text("Ton:", 14f), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        toneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voice.tones.map { it.label })
            setSelection(0)
        }
        toneRow.addView(toneSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(toneRow, lp())

        val preview = MaterialButton(this).apply {
            text = "▶ SESİ ÖNİZLE"
            setOnClickListener {
                lifecycleScope.launch {
                    runCatching {
                        if (!modelManager.supertonicReady()) error("Önce AI modellerini hazırla.")
                        status.text = "${selectedTone().label} tonunda ses oluşturuluyor…"
                        voice.speakTurkish(
                            "Merhaba. Ben Tercüman. Yabancı konuşmaları Türkçeye çevirmeye hazırım.",
                            selectedVoice(), selectedTone()
                        )
                        status.text = "Ses önizlemesi oynatıldı ✓"
                    }.onFailure { status.text = "Ses hatası: ${it.message ?: "bilinmeyen hata"}" }
                }
            }
        }
        col.addView(preview, lp())

        col.addView(card("ORİJİNAL", "Konuşma burada görünecek.").also { sourceText = it.getChildAt(0) as TextView }, lp())
        col.addView(card("TÜRKÇE", "Çeviri burada görünecek.").also { translatedText = it.getChildAt(0) as TextView }, lp())

        col.addView(text("Bölünmüş ekran için tasarlandı: başka uygulamayı üstte/yan tarafta açıp TERCÜMAN'ı diğer yarıda kullanabilirsin.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Sesli çıktı v0.7'de varsayılan olarak kapalıdır; önce çevirinin stabil çalışmasını doğruluyoruz. Açarsan seçtiğin doğal ses/ton kullanılır.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Hızlı çeviri: ses 1 saniyelik pencerelerle işlenir; cihazın Whisper hızına göre toplam gecikme değişebilir.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Telefon içi ses: Android kaynak uygulamanın yakalamaya izin vermesine bağlıdır. Sessiz akış açıkça bildirilecektir.", 12f, Color.rgb(170,184,197)))
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
    private fun selectedTone() = voice.tones[toneSpinner.selectedItemPosition.coerceIn(0, voice.tones.lastIndex)]

    private fun requestBasePermissions() {
        val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(req.toTypedArray())
    }

    private fun setModelUi(progress: Int, label: String) {
        if (isFinishing || isDestroyed) return
        modelProgress.progress = progress.coerceIn(0, 100)
        modelProgressText.text = label
    }

    private fun updateModelIndicator() {
        val whisperReady = modelManager.whisperReady()
        val superReady = modelManager.supertonicReady()
        when {
            whisperReady && superReady -> {
                setModelUi(100, "AI HAZIR ✓  •  Whisper ✓  •  Doğal Ses ✓")
                modelButton.text = "AI MODELLERİ HAZIR ✓"
            }
            whisperReady -> setModelUi(25, "AI: Whisper ✓  •  Doğal Ses bekleniyor")
            superReady -> setModelUi(75, "AI: Whisper bekleniyor  •  Doğal Ses ✓")
            else -> setModelUi(0, "AI modelleri henüz hazırlanmadı")
        }
    }

    private fun downloadModels() {
        if (preparingModels) return
        lifecycleScope.launch {
            preparingModels = true
            modelButton.isEnabled = false
            try {
                if (!modelManager.whisperReady()) {
                    setModelUi(0, "1/2 • Whisper hazırlanıyor… %0")
                    modelManager.ensureWhisper { p ->
                        val overall = (p * 0.25f).toInt()
                        runOnUiThread { setModelUi(overall, "1/2 • Whisper hazırlanıyor… %$p") }
                    }
                }
                setModelUi(25, "1/2 • Whisper hazır ✓")
                if (!modelManager.supertonicReady()) {
                    setModelUi(25, "2/2 • Doğal Türkçe ses modeli hazırlanıyor… %0")
                    modelManager.ensureSupertonic { p ->
                        val overall = 25 + (p * 0.75f).toInt()
                        runOnUiThread { setModelUi(overall, "2/2 • Doğal Ses hazırlanıyor… %$p") }
                    }
                }
                setModelUi(100, "AI HAZIR ✓  •  Whisper ✓  •  Doğal Ses ✓")
                modelButton.text = "AI MODELLERİ HAZIR ✓"
                status.text = "AI hazır. Şimdi DIŞ SES veya TELEFON SESİ seçebilirsin."
            } catch (e: Exception) {
                setModelUi(if (modelManager.whisperReady()) 25 else 0, "AI hazırlama tamamlanmadı")
                status.text = "Model hazırlama hatası: ${e.message ?: "bilinmeyen hata"}"
            } finally {
                preparingModels = false
                modelButton.isEnabled = true
                updateModelIndicator()
            }
        }
    }

    private fun toggleMic() {
        if (!modelManager.whisperReady()) { status.text = "Önce AI modellerini hazırla."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestBasePermissions(); return }
        if (micOn) {
            micChunker.stop(); micOn = false
            if (captureMode == CaptureMode.MIC) captureMode = CaptureMode.NONE
            clearChunkQueue(); clearTtsQueue()
            micButton.text = "🎤 DIŞ SES"; status.text = "Mikrofon durduruldu."
        } else {
            stopPhoneIfNeeded(); clearChunkQueue(); clearTtsQueue(); captureMode = CaptureMode.MIC
            try {
                micChunker.start(); micOn = true
                micButton.text = "⏹ DIŞ SESİ DURDUR"; status.text = "Dış ses dinleniyor…"
            } catch (e: Exception) {
                captureMode = CaptureMode.NONE; status.text = e.message ?: "Mikrofon başlatılamadı."
            }
        }
    }

    private fun togglePhone() {
        if (!modelManager.whisperReady()) { status.text = "Önce AI modellerini hazırla."; return }
        if (phoneOn) stopPhoneIfNeeded() else {
            stopPlaybackServiceSilently(); micChunker.stop(); micOn = false; captureMode = CaptureMode.NONE
            clearChunkQueue(); clearTtsQueue(); micButton.text = "🎤 DIŞ SES"
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun startPlaybackService(code: Int, data: Intent) {
        clearChunkQueue(); captureMode = CaptureMode.PHONE
        val i = Intent(this, PlaybackCaptureService::class.java)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, code)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        phoneOn = true; phoneButton.text = "⏹ TELEFON SESİNİ DURDUR"; status.text = "Telefon medya sesi dinleniyor…"
    }

    private fun stopPhoneIfNeeded() {
        val wasPhone = phoneOn
        captureMode = CaptureMode.NONE; clearChunkQueue(); clearTtsQueue(); stopPlaybackServiceSilently()
        phoneOn = false; phoneButton.text = "📱 TELEFON SESİ"
        if (wasPhone) status.text = "Telefon sesi durduruldu."
    }

    private fun stopPlaybackServiceSilently() { runCatching { stopService(Intent(this, PlaybackCaptureService::class.java)) } }

    private fun enqueueTts(text: String) {
        if (text.isBlank() || captureMode == CaptureMode.NONE || !speakSwitch.isChecked) return
        val task = SpeechTask(text.take(220), selectedVoice(), selectedTone())
        ttsQueue.tryReceive().getOrNull()
        if (ttsQueue.trySend(task).isFailure) { ttsQueue.tryReceive().getOrNull(); ttsQueue.trySend(task) }
    }

    private fun enqueueChunk(file: File, source: CaptureMode) {
        if (!file.exists() || captureMode != source) { file.delete(); return }
        if (chunkQueue.trySend(file).isSuccess) return
        val stale = chunkQueue.tryReceive().getOrNull(); stale?.delete()
        if (chunkQueue.trySend(file).isFailure) file.delete()
    }

    private fun clearChunkQueue() { while (true) { val stale = chunkQueue.tryReceive().getOrNull() ?: break; stale.delete() } }
    private fun clearTtsQueue() { while (ttsQueue.tryReceive().getOrNull() != null) { }; voice.stop() }

    private suspend fun processTtsQueue() {
        for (task in ttsQueue) {
            if (captureMode == CaptureMode.NONE || !speakSwitch.isChecked) continue
            runCatching { voice.speakTurkish(task.text, task.profile, task.tone) }
                .onFailure { if (captureMode != CaptureMode.NONE) status.text = "Ses hatası: ${it.message ?: "bilinmeyen hata"}" }
        }
    }

    private suspend fun processQueue() {
        for (wav in chunkQueue) {
            try {
                if (captureMode == CaptureMode.NONE) { wav.delete(); continue }
                status.text = "🧠 Konuşma algılandı • yazıya çevriliyor…"
                val original = withContext(Dispatchers.Default) { whisper.transcribe(wav) }
                wav.delete()
                if (captureMode == CaptureMode.NONE || original.length < 2) continue
                sourceText.text = "ORİJİNAL\n\n$original"
                status.text = "🔄 Türkçeye çevriliyor…"
                val (tr, lang) = translator.toTurkish(original)
                if (captureMode == CaptureMode.NONE) continue
                translatedText.text = "TÜRKÇE  •  ${lang.uppercase()}\n\n$tr"
                lastTranslationAt = System.currentTimeMillis()
                status.text = if (speakSwitch.isChecked) "🇹🇷 Çeviri hazır • 🔊 ses oluşturuluyor…" else "🇹🇷 Çeviri hazır ✓"
                if (speakSwitch.isChecked && modelManager.supertonicReady() && captureMode != CaptureMode.NONE) enqueueTts(tr)
            } catch (e: Exception) {
                wav.delete()
                if (captureMode != CaptureMode.NONE) status.text = "Çeviri hatası: ${e.message ?: "bilinmeyen hata"}"
            }
        }
    }

    private fun registerPlaybackReceiver() {
        val filter = IntentFilter().apply { addAction(PlaybackCaptureService.ACTION_CHUNK); addAction(PlaybackCaptureService.ACTION_STATUS) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playbackReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(playbackReceiver, filter)
    }

    private fun updateStatus() {
        status.text = when {
            preparingModels -> "AI modelleri hazırlanıyor…"
            !modelManager.whisperReady() || !modelManager.supertonicReady() -> "İlk kullanım: AI MODELLERİNİ HAZIRLA'ya dokun."
            else -> "Hazır ✓"
        }
    }

    override fun onDestroy() {
        captureMode = CaptureMode.NONE
        micChunker.stop(); stopPlaybackServiceSilently(); clearChunkQueue(); clearTtsQueue()
        voice.release(); whisper.release(); translator.close()
        runCatching { unregisterReceiver(playbackReceiver) }
        super.onDestroy()
    }
}
