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
    private lateinit var translationSizeText: TextView
    private lateinit var modelButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var speakSwitch: Switch

    private var micOn = false
    private var phoneOn = false
    private var ready = false
    private var lastEnglish = ""
    private var sentenceSequence = 0L
    private var latestSentenceId = 0L
    private val translationMutex = Mutex()
    private val audioMutex = Mutex()
    private val translatedHistory = ArrayDeque<String>()
    private var translationTextSize = 20f

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
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 27, 51))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
        }

        col.addView(text("TERCÜMAN", 26f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(text("v1.0.8 • streaming konuşma motoru • cihaz içi", 12f, Color.rgb(170,184,197)))
        status = text("Hazırlanıyor…", 13f, Color.rgb(85,214,190))
        col.addView(status)

        modelButton = compactButton("AI HAZIRLA") { downloadModels() }
        col.addView(modelButton, lp(8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = compactButton("🎤 DIŞ SES") { toggleMic() }
        phoneButton = compactButton("📱 TELEFON SESİ") { togglePhone() }
        row.addView(micButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
        row.addView(phoneButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        col.addView(row, lp(6))

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speakSwitch = Switch(this).apply { text = "Türkçeyi seslendir"; setTextColor(Color.WHITE); isChecked = false }
        voiceRow.addView(speakSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val preview = compactButton("▶ ÖNİZLEME") { previewSpeech() }
        voiceRow.addView(preview, LinearLayout.LayoutParams(dp(125), dp(46)))
        col.addView(voiceRow, lp(4))

        col.addView(card("ORİJİNAL • CANLI", "Konuşma bekleniyor…", 16f, dp(90)).also { sourceText = it.getChildAt(0) as TextView }, lp(8))

        val translationTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        translationTitleRow.addView(text("TÜRKÇE • ÇEVİRİ", 18f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val minus = smallButton("−") { setTranslationTextSize(translationTextSize - 2f) }
        translationSizeText = text("20", 14f, Color.rgb(170,184,197)).apply { gravity = Gravity.CENTER }
        val plus = smallButton("+") { setTranslationTextSize(translationTextSize + 2f) }
        translationTitleRow.addView(minus, LinearLayout.LayoutParams(dp(44), dp(42)))
        translationTitleRow.addView(translationSizeText, LinearLayout.LayoutParams(dp(34), dp(42)))
        translationTitleRow.addView(plus, LinearLayout.LayoutParams(dp(44), dp(42)))
        col.addView(translationTitleRow, lp(6))

        col.addView(translationCard().also { translatedText = it.getChildAt(0) as TextView }, lp(4))
        root.addView(col)
        return root
    }

    private fun compactButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 13f
        setOnClickListener { action() }
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun smallButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { action() }
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }

    private fun translationCard(): MaterialCardView {
        val tv = TextView(this).apply {
            text = "TÜRKÇE • BEKLENİYOR\n\nKonuşma bekleniyor…"
            textSize = translationTextSize
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            minHeight = dp(360)
            gravity = Gravity.TOP or Gravity.START
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(Color.rgb(20,42,74))
            strokeWidth = 1
            strokeColor = Color.rgb(85,214,190)
            addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun card(title: String, initial: String, size: Float, minHeight: Int): MaterialCardView {
        val tv = TextView(this).apply {
            text = "$title\n\n$initial"
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            this.minHeight = minHeight
        }
        return MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.rgb(20,42,74))
            strokeWidth = 1
            strokeColor = Color.rgb(85,214,190)
            addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun setTranslationTextSize(newSize: Float) {
        translationTextSize = newSize.coerceIn(14f, 20f)
        translationSizeText.text = translationTextSize.toInt().toString()
        translatedText.textSize = translationTextSize
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

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
                    sourceText.text = "ORİJİNAL • CANLI\n\n$partial"
                    if (!endpoint) {
                        translatedText.text = "TÜRKÇE • BEKLENİYOR\n\nCümle tamamlanıyor…"
                    }
                    status.text = if (endpoint) "🧠 Cümle tamamlandı • Türkçe hazırlanıyor…" else "🟢 Canlı konuşma algılanıyor…"
                }
            }

            if (endpoint && !SentenceStabilizer.looksIncomplete(partial)) {
                val id = ++sentenceSequence
                latestSentenceId = id
                withContext(Dispatchers.Main) {
                    translatedText.text = "TÜRKÇE • ÇEVRİLİYOR…\n\n$partial"
                    status.text = "🧠 Konuşma düzenleniyor • Türkçe hazırlanıyor…"
                }
                translateFinalSentence(partial, id)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { status.text = "Streaming ASR hatası: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun translateFinalSentence(text: String, sentenceId: Long) {
        lifecycleScope.launch {
            translationMutex.withLock {
                try {
                    val turns = ConversationEngine.format(text)
                    if (turns.isEmpty()) return@withLock
                    val translated = mutableListOf<String>()
                    for (turn in turns) {
                        val tr = withContext(Dispatchers.IO) { translator.translateEnglishToTurkish(turn.text) }.trim()
                        if (tr.isNotBlank()) translated += "👤 ${turn.speaker}\n$tr"
                    }
                    if (translated.isEmpty()) return@withLock
                    val fullTranslation = translated.joinToString("\n\n")

                    withContext(Dispatchers.Main) {
                        if (sentenceId != latestSentenceId) return@withContext
                        translatedText.text = "TÜRKÇE • STABİL\n\n$fullTranslation"
                        status.text = "🇹🇷 Çeviri hazır ✓ • Yeni konuşma bekleniyor"
                    }

                    if (speakSwitch.isChecked && ttsReady) {
                        previewTts?.speak(
                            translated.filter { it.isNotBlank() }.joinToString(" ") { it.substringAfter('\n') },
                            TextToSpeech.QUEUE_ADD, null, "final_${sentenceId}_${System.currentTimeMillis()}"
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (sentenceId == latestSentenceId) {
                            translatedText.text = "TÜRKÇE • ÇEVİRİ HATASI\n\n⚠️ ${e.message ?: e.javaClass.simpleName}"
                            status.text = "Çeviri hatası"
                        }
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
