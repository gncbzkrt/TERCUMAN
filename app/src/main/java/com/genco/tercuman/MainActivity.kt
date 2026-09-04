package com.genco.tercuman

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var streaming: StreamingEnglishEngine
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
    private var ready = false
    private var lastEnglish = ""
    private var lastTranslated = ""
    private var translationJob: Job? = null
    private var lastTranslationAt = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStatus() }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startPlaybackService(result.resultCode, result.data!!)
        else status.text = "Telefon sesi izni verilmedi."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        translator = TranslationEngine()
        streaming = StreamingEnglishEngine(modelManager.streamingDir)
        micChunker = MicrophoneChunker(this) { pcm, rate -> StreamingHub.emit(pcm, rate) }
        setContentView(buildUi())
        initPreviewTts()
        requestBasePermissions()
        StreamingHub.setListener { chunk ->
            if (!ready) return@setListener
            lifecycleScope.launch(Dispatchers.Default) { handleAudio(chunk.pcm, chunk.sampleRate) }
        }
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
        col.addView(text("v1.0 deneysel • gerçek streaming ASR • cihaz içi", 13f, Color.rgb(170,184,197)))
        status = text("Hazırlanıyor…", 14f, Color.rgb(85,214,190)); col.addView(status)

        modelButton = MaterialButton(this).apply { text = "STREAMING AI'YI HAZIRLA"; setOnClickListener { downloadModels() } }
        col.addView(modelButton, lp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = MaterialButton(this).apply { text = "🎤 DIŞ SES"; setOnClickListener { toggleMic() } }
        phoneButton = MaterialButton(this).apply { text = "📱 TELEFON SESİ"; setOnClickListener { togglePhone() } }
        row.addView(micButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(phoneButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row, lp())

        speakSwitch = Switch(this).apply { text = "Türkçeyi seslendir"; setTextColor(Color.WHITE); isChecked = false }
        col.addView(speakSwitch, lp())
        val preview = MaterialButton(this).apply {
            text = "▶ SES ÖNİZLEME"
            setOnClickListener { previewSpeech() }
        }
        col.addView(preview, lp())

        col.addView(card("ORİJİNAL • İNGİLİZCE", "Canlı konuşma burada görünecek." ).also { sourceText = it.getChildAt(0) as TextView }, lp())
        col.addView(card("TÜRKÇE", "Canlı çeviri burada görünecek." ).also { translatedText = it.getChildAt(0) as TextView }, lp())
        col.addView(text("v1.0 ilk deney: İngilizce → Türkçe. Ses, tek bir OnlineStream'e 100 ms parçalar halinde beslenir; parçalar ayrı ayrı Whisper'a gönderilmez.", 12f, Color.rgb(170,184,197)))
        col.addView(text("Telefon sesi Android AudioPlaybackCapture izinleriyle alınır; kaynak uygulama yakalamayı engellerse ses gelmeyebilir.", 12f, Color.rgb(170,184,197)))
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
        status.text = "Ses önizlemesi oynatılıyor…"
        previewTts?.setSpeechRate(0.98f)
        previewTts?.speak("Merhaba. Ben Tercüman. Yabancı konuşmaları Türkçeye çevirmeye hazırım.", TextToSpeech.QUEUE_FLUSH, null, "tercuman_preview")
    }

    private fun downloadModels() {
        lifecycleScope.launch {
            modelButton.isEnabled = false
            try {
                status.text = "Gerçek streaming İngilizce modeli indiriliyor…"
                modelManager.ensureStreamingEnglish { p -> runOnUiThread { status.text = "Streaming ASR modeli: %$p" } }
                status.text = "Streaming ASR hazır ✓ • İngilizce → Türkçe modeli hazırlanıyor…"
                translator.prepareEnglishTurkish { msg -> runOnUiThread { status.text = msg } }
                streaming.start()
                ready = true
                status.text = "AI HAZIR ✓ • GERÇEK STREAMING ✓ • EN → TR ✓"
                modelButton.text = "AI HAZIR ✓"
            } catch (e: Exception) {
                ready = false
                status.text = "⚠️ Hazırlık hatası: ${e.message ?: e.javaClass.simpleName}"
            } finally { modelButton.isEnabled = true }
        }
    }

    private fun toggleMic() {
        if (!ready) { status.text = "Önce STREAMING AI'YI HAZIRLA."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestBasePermissions(); return }
        if (micOn) {
            micChunker.stop(); micOn = false; micButton.text = "🎤 DIŞ SES"; status.text = "Mikrofon durduruldu."
        } else {
            stopPhoneIfNeeded()
            try {
                micChunker.start(); micOn = true; micButton.text = "⏹ DIŞ SESİ DURDUR"; status.text = "🎙️ Canlı mikrofon dinleniyor…"
            } catch (e: Exception) { status.text = e.message ?: "Mikrofon başlatılamadı." }
        }
    }

    private fun togglePhone() {
        if (!ready) { status.text = "Önce STREAMING AI'YI HAZIRLA."; return }
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
        phoneOn = true; phoneButton.text = "⏹ TELEFON SESİNİ DURDUR"; status.text = "📱 Telefon sesi canlı dinleniyor…"
    }

    private fun stopPhoneIfNeeded() {
        if (!phoneOn) return
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.STOP_ACTION))
        phoneOn = false; phoneButton.text = "📱 TELEFON SESİ"; status.text = "Telefon sesi durduruldu."
    }

    private suspend fun handleAudio(pcm: ShortArray, sampleRate: Int) {
        try {
            val (partial, endpoint) = streaming.acceptPcm16(pcm, sampleRate)
            if (partial.isBlank() || partial == lastEnglish) return
            lastEnglish = partial
            withContext(Dispatchers.Main) {
                sourceText.text = "ORİJİNAL • İNGİLİZCE\n\n$partial"
                status.text = if (endpoint) "🧠 Cümle tamamlandı • Türkçeye çevriliyor…" else "🟢 Canlı konuşma algılanıyor…"
            }
            scheduleTranslation(partial, endpoint)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { status.text = "Streaming ASR hatası: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun scheduleTranslation(text: String, endpoint: Boolean) {
        val now = System.currentTimeMillis()
        if (!endpoint && now - lastTranslationAt < 650L) return
        lastTranslationAt = now
        translationJob?.cancel()
        translationJob = lifecycleScope.launch {
            delay(if (endpoint) 50L else 120L)
            try {
                val tr = withContext(Dispatchers.IO) { translator.translateEnglishToTurkish(text) }
                if (tr.isBlank() || tr == lastTranslated) return@launch
                lastTranslated = tr
                translatedText.text = "TÜRKÇE\n\n$tr"
                status.text = if (endpoint) "🇹🇷 Çeviri hazır ✓" else "🇹🇷 Canlı çeviri…"
                if (speakSwitch.isChecked && ttsReady) previewTts?.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "live_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                translatedText.text = "TÜRKÇE\n\n⚠️ Çeviri: ${e.message ?: e.javaClass.simpleName}"
                status.text = "Çeviri hatası"
            }
        }
    }

    private fun updateStatus() {
        if (!modelManager.streamingReady()) status.text = "İlk kullanım: STREAMING AI'YI HAZIRLA'ya dokun."
    }

    override fun onDestroy() {
        micChunker.stop(); stopPhoneIfNeeded()
        StreamingHub.setListener(null)
        streaming.stop()
        previewTts?.stop(); previewTts?.shutdown(); previewTts = null
        translator.close()
        super.onDestroy()
    }
}
