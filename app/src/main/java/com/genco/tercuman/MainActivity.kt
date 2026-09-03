package com.genco.tercuman

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var whisper: WhisperEngine
    private lateinit var translator: TranslationEngine
    private lateinit var micChunker: MicrophoneChunker
    private var previewTts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var status: TextView
    private lateinit var sourceText: TextView
    private lateinit var translatedText: TextView
    private lateinit var modelButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var speakSwitch: Switch

    private var micOn = false
    private var phoneOn = false
    private var translationReady = false
    private val chunkQueue = Channel<File>(capacity = Channel.UNLIMITED)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStatus() }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startPlaybackService(result.resultCode, result.data!!)
        else status.text = "Telefon sesi izni verilmedi."
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackCaptureService.ACTION_CHUNK) {
                intent.getStringExtra(PlaybackCaptureService.EXTRA_PATH)?.let { chunkQueue.trySend(File(it)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        whisper = WhisperEngine(this, modelManager)
        translator = TranslationEngine()
        micChunker = MicrophoneChunker(this) { chunkQueue.trySend(it) }
        setContentView(buildUi())
        initPreviewTts()
        requestBasePermissions()
        registerPlaybackReceiver()
        lifecycleScope.launch { processQueue() }
        updateStatus()
    }

    private fun initPreviewTts() {
        previewTts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = previewTts?.setLanguage(Locale("tr", "TR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ttsReady = false
                previewTts?.setSpeechRate(0.98f)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(12, 27, 51)); isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(20)) }
        col.addView(text("TERCÜMAN", 28f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(text("Stabil çekirdek • cihaz içi çeviri • sıfır API ücreti", 13f, Color.rgb(170,184,197)))
        status = text("Hazırlanıyor…", 14f, Color.rgb(85,214,190)); col.addView(status)

        modelButton = MaterialButton(this).apply { text = "AI MODELİNİ HAZIRLA"; setOnClickListener { downloadModels() } }
        col.addView(modelButton, lp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = MaterialButton(this).apply { text = "🎤 DIŞ SES"; setOnClickListener { toggleMic() } }
        phoneButton = MaterialButton(this).apply { text = "📱 TELEFON SESİ"; setOnClickListener { togglePhone() } }
        row.addView(micButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(phoneButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row, lp())

        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voiceRow.addView(text("Ses:", 14f), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Kadın 1", "Kadın 2", "Kadın 3", "Erkek 1", "Erkek 2", "Erkek 3"))
        }
        voiceRow.addView(voiceSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speakSwitch = Switch(this).apply { text = "Sesli"; setTextColor(Color.WHITE); isChecked = false }
        voiceRow.addView(speakSwitch)
        col.addView(voiceRow, lp())

        val preview = MaterialButton(this).apply {
            text = "▶ SESİ ÖNİZLE"
            setOnClickListener { previewSpeech() }
        }
        col.addView(preview, lp())

        col.addView(card("ORİJİNAL", "Konuşma burada görünecek.").also { sourceText = it.getChildAt(0) as TextView }, lp())
        col.addView(card("TÜRKÇE", "Çeviri burada görünecek.").also { translatedText = it.getChildAt(0) as TextView }, lp())
        col.addView(text("v0.10.0: İngilizce kaynak modu. Whisper language=en ile çalışır; kısa ses parçalarında Japonca vb. yanlış dil algılaması engellenir. İngilizce→Türkçe ML Kit modeli önceden hazırlanır.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Telefon içi ses, Android'in kaynak uygulamanın yakalanmasına izin vermesine bağlıdır.", 12f, Color.rgb(170,184,197)))
        root.addView(col)
        return root
    }

    private fun card(title: String, initial: String): MaterialCardView {
        val tv = TextView(this).apply { text = "$title\n\n$initial"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(16), dp(16), dp(16), dp(16)); minHeight = dp(130) }
        return MaterialCardView(this).apply { radius = dp(14).toFloat(); setCardBackgroundColor(Color.rgb(20,42,74)); strokeWidth = 1; strokeColor = Color.rgb(85,214,190); addView(tv) }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }

    private fun requestBasePermissions() {
        val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(req.toTypedArray())
    }

    private fun previewSpeech() {
        if (!ttsReady) { status.text = "Telefonun Türkçe konuşma motoru hazır değil."; return }
        val text = "Merhaba. Ben Tercüman. Yabancı konuşmaları Türkçeye çevirmeye hazırım."
        status.text = "Ses önizlemesi oynatılıyor…"
        val rate = when (speakSwitch.text.toString()) { else -> 0.98f }
        previewTts?.setSpeechRate(rate)
        previewTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tercuman_preview")
    }

    private fun downloadModels() {
        lifecycleScope.launch {
            modelButton.isEnabled = false
            try {
                if (!modelManager.whisperReady()) {
                    status.text = "Whisper indiriliyor…"
                    modelManager.ensureWhisper { p -> runOnUiThread { status.text = "Whisper: %$p" } }
                }
                status.text = "Whisper hazır ✓ • İngilizce kaynak modu hazırlanıyor…"
                translator.prepareEnglishTurkish { msg -> runOnUiThread { status.text = msg } }
                translationReady = true
                status.text = "WHISPER ✓ • İNGİLİZCE KAYNAK ✓ • TÜRKÇE ÇEVİRİ ✓"
                modelButton.text = "AI HAZIR ✓"
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                status.text = "⚠️ Çeviri modeli hazırlanamadı: $reason"
                translatedText.text = "TÜRKÇE\n\nÇeviri modeli hazır değil.\nAI MODELİNİ HAZIRLA'ya tekrar dokunun."
            } finally { modelButton.isEnabled = true }
        }
    }

    private fun toggleMic() {
        if (!modelManager.whisperReady() || !translationReady) { status.text = "Önce AI MODELİNİ HAZIRLA."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestBasePermissions(); return }
        if (micOn) {
            micChunker.stop(); micOn = false; micButton.text = "🎤 DIŞ SES"; status.text = "Mikrofon durduruldu."
        } else {
            stopPhoneIfNeeded();
            try {
                micChunker.start(); micOn = true; micButton.text = "⏹ DIŞ SESİ DURDUR"; status.text = "Dış ses dinleniyor…"
            } catch (e: Exception) { status.text = e.message ?: "Mikrofon başlatılamadı." }
        }
    }

    private fun togglePhone() {
        if (!modelManager.whisperReady() || !translationReady) { status.text = "Önce AI MODELİNİ HAZIRLA."; return }
        if (phoneOn) stopPhoneIfNeeded() else {
            micChunker.stop(); micOn = false; micButton.text = "🎤 DIŞ SES"
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun startPlaybackService(code: Int, data: Intent) {
        val i = Intent(this, PlaybackCaptureService::class.java)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, code)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        phoneOn = true; phoneButton.text = "⏹ TELEFON SESİNİ DURDUR"; status.text = "Telefon medya sesi dinleniyor…"
    }

    private fun stopPhoneIfNeeded() {
        if (!phoneOn) return
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.STOP_ACTION))
        phoneOn = false; phoneButton.text = "📱 TELEFON SESİ"; status.text = "Telefon sesi durduruldu."
    }

    private suspend fun processQueue() {
        for (wav in chunkQueue) {
            try {
                status.text = "🧠 Ses alındı • yazıya çevriliyor…"
                val original = withContext(Dispatchers.Default) { whisper.transcribe(wav) }
                wav.delete()
                if (original.length < 2) continue
                sourceText.text = "ORİJİNAL\n\n$original"
                status.text = "🔄 Türkçeye çevriliyor…"
                val (tr, lang) = translator.toTurkish(original) { msg ->
                    runOnUiThread { status.text = "🔄 $msg" }
                }
                translatedText.text = "TÜRKÇE  •  ${lang.uppercase()}\n\n$tr"
                status.text = "🇹🇷 Çeviri hazır ✓"
                if (speakSwitch.isChecked && ttsReady) {
                    previewTts?.speak(tr, TextToSpeech.QUEUE_ADD, null, "tercuman_${System.currentTimeMillis()}")
                }
            } catch (e: Exception) {
                wav.delete()
                status.text = "Çeviri hatası: ${e.message ?: "bilinmeyen hata"}"
            }
        }
    }

    private fun registerPlaybackReceiver() {
        val filter = IntentFilter(PlaybackCaptureService.ACTION_CHUNK)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playbackReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(playbackReceiver, filter)
    }

    private fun updateStatus() {
        status.text = if (!modelManager.whisperReady() || !translationReady) "İlk kullanım: AI MODELİNİ HAZIRLA'ya dokun." else "Hazır ✓"
    }

    override fun onDestroy() {
        micChunker.stop(); stopPhoneIfNeeded()
        previewTts?.stop(); previewTts?.shutdown(); previewTts = null
        whisper.release(); translator.close()
        runCatching { unregisterReceiver(playbackReceiver) }
        super.onDestroy()
    }
}
