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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var sentenceSequence = 0L
    private val translationMutex = Mutex()
    private val audioMutex = Mutex()
    private val translatedHistory = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStatus() }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startPlaybackService(result.resultCode, result.data!!)
        else status.text = "Telefon sesi izni verilmedi."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        translator = TranslationEngine()
        streaming = StreamingEnglishEngine(this, modelManager.streamingDir)
        micChunker = MicrophoneChunker(this) { pcm, rate -> StreamingHub.emit(pcm, rate) }
        setContentView(buildUi())
        initPreviewTts()
        requestBasePermissions()
        StreamingHub.setListener { chunk ->
            if (!ready) return@setListener
            lifecycleScope.launch(Dispatchers.Default) { audioMutex.withLock { handleAudio(chunk.pcm, chunk.sampleRate) } }
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
        col.addView(text("v1.0.5 • gerçek streaming ASR • cihaz içi", 13f, Color.rgb(170,184,197)))
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

        col.addView(card("ORİJİNAL • İNGİLİZCE • CANLI", "Konuşma burada anlık görünecek." ).also { sourceText = it.getChildAt(0) as TextView }, lp())
        col.addView(card("TÜRKÇE • STABİL ÇEVİRİ", "Cümle tamamlandığında temiz çeviri burada kalır." ).also { translatedText = it.getChildAt(0) as TextView }, lp())
        col.addView(text("v1.0.5: Streaming hızını korur; her tamamlanan İngilizce cümleyi tek çeviri işlemi olarak sırayla işler ve eski çevirinin yeni cümleyle karışmasını engeller.", 12f, Color.rgb(170,184,197)))
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
            val result = streaming.acceptPcm16(pcm, sampleRate)
            val partial = result.first.trim()
            val endpoint = result.second
            if (partial.isBlank()) return

            if (partial != lastEnglish) {
                lastEnglish = partial
                withContext(Dispatchers.Main) {
                    sourceText.text = "ORİJİNAL • İNGİLİZCE • CANLI\n\n$partial"
                    status.text = if (endpoint) {
                        "🧠 Cümle tamamlandı • Türkçe hazırlanıyor…"
                    } else {
                        "🟢 Canlı konuşma algılanıyor…"
                    }
                }
            }

            if (endpoint) {
                val id = ++sentenceSequence
                translateFinalSentence(partial, id)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                status.text = "Streaming ASR hatası: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun translateFinalSentence(text: String, sentenceId: Long) {
        lifecycleScope.launch {
            translationMutex.withLock {
                try {
                    withContext(Dispatchers.Main) {
                        translatedText.text = "TÜRKÇE • ÇEVRİLİYOR…\n\n$text"
                    }

                    val segments = SentenceStabilizer.splitAndNormalize(text)
                    if (segments.isEmpty()) return@withLock

                    val translatedSegments = mutableListOf<String>()
                    for (segment in segments) {
                        val tr = withContext(Dispatchers.IO) {
                            translator.translateEnglishToTurkish(segment)
                        }.trim()
                        if (tr.isNotBlank()) translatedSegments += tr
                    }
                    if (translatedSegments.isEmpty()) return@withLock

                    val fullTranslation = translatedSegments.joinToString(" ")
                    val previousHistory = translatedHistory.toList().asReversed()
                    translatedHistory.addLast(fullTranslation)
                    while (translatedHistory.size > 3) translatedHistory.removeFirst()

                    withContext(Dispatchers.Main) {
                        val historyText = previousHistory.take(2).joinToString("\n\n")
                        val previousBlock = if (historyText.isBlank()) "" else "\n\n──────\nÖNCEKİ ÇEVİRİLER\n\n$historyText"
                        translatedText.text = "TÜRKÇE • STABİL\n\n$fullTranslation$previousBlock"
                        status.text = "🇹🇷 Çeviri hazır ✓ • Yeni cümle bekleniyor"
                    }

                    if (speakSwitch.isChecked && ttsReady) {
                        previewTts?.speak(
                            fullTranslation,
                            TextToSpeech.QUEUE_ADD,
                            null,
                            "final_${sentenceId}_${System.currentTimeMillis()}"
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        translatedText.text = "TÜRKÇE • ÇEVİRİ HATASI\n\n⚠️ ${e.message ?: e.javaClass.simpleName}"
                        status.text = "Çeviri hatası"
                    }
                }
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
