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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var streaming: StreamingEnglishEngine
    private lateinit var translator: TranslationEngine
    private lateinit var micChunker: MicrophoneChunker
    private lateinit var diarization: SpeakerDiarizationEngine
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
    private val audioMutex = kotlinx.coroutines.sync.Mutex()
    private var translationTextSize = 20f
    private var lastCommittedEnglish = ""
    private val originalHistory = mutableListOf<String>()
    private val translationHistory = mutableListOf<String>()

    private data class ConversationLine(
        val sentenceId: Long,
        var speaker: String,
        val original: String,
        var translated: String = ""
    )

    /*
     * Each completed sentence has its own identity.
     * Translation and speaker diarization may finish at different times.
     */
    private val conversationLines = linkedMapOf<Long, ConversationLine>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStatus() }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startPlaybackService(result.resultCode, result.data!!)
        else status.text = "Telefon sesi izni verilmedi."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        diarization = SpeakerDiarizationEngine(this, modelManager.modelRoot)
        translator = TranslationEngine()
        streaming = StreamingEnglishEngine(modelManager.streamingDir)
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
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            text("TERCÜMAN", 25f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        modelButton = MaterialButton(this).apply {
            text = "AI HAZIRLA"
            textSize = 10f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { downloadModels() }
        }
        header.addView(modelButton, LinearLayout.LayoutParams(dp(112), dp(38)))
        col.addView(header)

        status = text("Hazırlanıyor…", 12f, Color.rgb(85,214,190))
        col.addView(status, lp(0))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        micButton = compactButton("🎤 DIŞ SES") { toggleMic() }
        phoneButton = compactButton("📱 TELEFON SESİ") { togglePhone() }
        row.addView(micButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
        row.addView(phoneButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
        col.addView(row, lp(5))

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speakSwitch = Switch(this).apply {
            text = "Türkçeyi seslendir"
            setTextColor(Color.WHITE)
            isChecked = false
            textSize = 14f
        }
        voiceRow.addView(speakSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val preview = compactButton("▶ ÖNİZLEME") { previewSpeech() }
        voiceRow.addView(preview, LinearLayout.LayoutParams(dp(112), dp(42)))
        col.addView(voiceRow, lp(2))

        col.addView(card("ORİJİNAL • CANLI", "Konuşma bekleniyor…", 15f, dp(82)).also {
            sourceText = it.getChildAt(0) as TextView
        }, lp(4))

        val translationTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        translationTitleRow.addView(
            text("TÜRKÇE • ÇEVİRİ", 19f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val minus = smallButton("−") { setTranslationTextSize(translationTextSize - 2f) }
        translationSizeText = text("20", 14f, Color.rgb(170,184,197)).apply { gravity = Gravity.CENTER }
        val plus = smallButton("+") { setTranslationTextSize(translationTextSize + 2f) }
        translationTitleRow.addView(minus, LinearLayout.LayoutParams(dp(44), dp(42)))
        translationTitleRow.addView(translationSizeText, LinearLayout.LayoutParams(dp(34), dp(42)))
        translationTitleRow.addView(plus, LinearLayout.LayoutParams(dp(44), dp(42)))
        col.addView(translationTitleRow, lp(4))

        col.addView(translationCard().also { translatedText = it.getChildAt(0) as TextView }, lp(2))
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
            minHeight = dp(560)
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
            status.text = "Konuşmacı modeli hazırlanıyor…"
            modelManager.ensureDiarization { p ->
                runOnUiThread { status.text = "Konuşmacı modeli indiriliyor… $p%" }
            }
            diarization.start()
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
        if (!ready) { status.text = "Önce sağ üstten AI HAZIRLA."; return }
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
        if (!ready) { status.text = "Önce sağ üstten AI HAZIRLA."; return }
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

    private var lastPartialShownAt = 0L
    private var lastPartialShown = ""

    private suspend fun handleAudio(pcm: ShortArray, sampleRate: Int) {
        try {
            /*
             * IMPORTANT:
             * Diarization only receives the audio here.
             * Heavy speaker analysis is NEVER performed inside this
             * real-time audio path.
             */
            diarization.addPcm16(pcm, sampleRate)

            val result = streaming.acceptPcm16(pcm, sampleRate)
            val partial = result.text.trim()
            val endpoint = result.endpoint

            if (partial.isBlank()) return

            /*
             * PARTIAL ASR:
             *
             * Show a lightweight live preview, but NEVER translate it
             * and NEVER append it to conversation history.
             *
             * Throttle UI updates so the screen does not churn
             * word-by-word.
             */
            if (!endpoint) {
                val now = System.currentTimeMillis()

                val changedEnough =
                    lastPartialShown.isBlank() ||
                    partial != lastPartialShown

                val enoughTimePassed =
                    now - lastPartialShownAt >= 300L

                if (changedEnough && enoughTimePassed) {
                    lastPartialShown = partial
                    lastPartialShownAt = now

                    withContext(Dispatchers.Main) {
                        status.text = "🟢 Konuşma algılanıyor..."

                        sourceText.text =
                            "ORİJİNAL • CANLI\n\n$partial"
                    }
                }

                return
            }

            /*
             * ENDPOINT:
             *
             * Only a completed/stable sentence reaches translation.
             */
            val stableEnglish = partial
                .replace(Regex("\\s+"), " ")
                .trim()

            if (
                stableEnglish.isBlank() ||
                stableEnglish == lastCommittedEnglish
            ) {
                return
            }

            lastCommittedEnglish = stableEnglish

            val sentenceId = ++latestSentenceId

            lastPartialShown = ""
            lastPartialShownAt = 0L

            /*
             * Do NOT run currentSpeaker() here.
             *
             * It is an offline diarization operation and can be
             * considerably heavier than streaming ASR.
             *
             * It is launched independently below.
             */
            withContext(Dispatchers.Main) {
                status.text = "🟡 Cümle tamamlandı • Çeviriliyor..."
            }

            scheduleFinalTranslation(
                text = stableEnglish,
                sentenceId = sentenceId
            )

            /*
             * Speaker analysis runs independently.
             * It can no longer block the incoming audio/ASR chain.
             *
             * The current speaker result is used to update the
             * conversation label when available.
             */
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val speaker = diarization.currentSpeaker()

                    withContext(Dispatchers.Main) {
                        /*
                         * Speaker result is intentionally handled
                         * separately from ASR/translation timing.
                         */
                        updateLatestSpeakerLabel(
                            sentenceId = sentenceId,
                            speaker = speaker
                        )
                    }
                } catch (_: Exception) {
                    // Speaker analysis must never interrupt translation.
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                status.text =
                    "🔴 Streaming ASR hatası: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun scheduleFinalTranslation(
        text: String,
        sentenceId: Long
    ) {
        /*
         * v1.2.0:
         *
         * Never cancel a previous translation.
         * Every completed sentence owns its own translation job.
         */
        lifecycleScope.launch {
            try {
                /*
                 * Create the conversation line on Main.
                 * This keeps conversationLines thread-safe.
                 *
                 * Speaker is initially A only as a temporary placeholder.
                 * Real local diarization may update it asynchronously.
                 */
                conversationLines[sentenceId] = ConversationLine(
                    sentenceId = sentenceId,
                    speaker = "A",
                    original = text
                )

                renderConversationHistory()

                val translated = withContext(Dispatchers.IO) {
                    translator
                        .translateEnglishToTurkish(text)
                        .trim()
                }

                if (translated.isBlank()) {
                    return@launch
                }

                val line = conversationLines[sentenceId]
                    ?: return@launch

                line.translated = translated

                renderConversationHistory()

                status.text = "🟢 Çeviri hazır ✓"

                if (speakSwitch.isChecked && ttsReady) {
                    previewTts?.speak(
                        translated,
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "final_${sentenceId}_${System.currentTimeMillis()}"
                    )
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "🟠 Çeviri hatası"
                }
            }
        }
    }

    /**
     * Speaker diarization finishes independently from translation.
     *
     * Only the matching sentenceId is updated.
     * A late speaker result can therefore never overwrite another
     * conversation line.
     */
    private fun updateLatestSpeakerLabel(
        sentenceId: Long,
        speaker: String
    ) {
        if (speaker != "A" && speaker != "B") {
            return
        }

        val line = conversationLines[sentenceId]
            ?: return

        line.speaker = speaker

        renderConversationHistory()
    }

    /**
     * Render only completed conversation lines.
     *
     * Streaming partial ASR is intentionally NOT stored here.
     */
    private fun renderConversationHistory() {
        val ordered = conversationLines
            .values
            .sortedBy { it.sentenceId }

        originalHistory.clear()
        translationHistory.clear()

        for (line in ordered) {
            originalHistory +=
                "👤 ${line.speaker}\n${line.original}"

            if (line.translated.isNotBlank()) {
                translationHistory +=
                    "👤 ${line.speaker}\n${line.translated}"
            }
        }

        sourceText.text =
            "ORİJİNAL • KONUŞMA GEÇMİŞİ\n\n" +
                originalHistory.joinToString("\n\n")

        translatedText.text =
            "TÜRKÇE • KONUŞMA GEÇMİŞİ\n\n" +
                if (translationHistory.isEmpty()) {
                    "Çeviri hazırlanıyor..."
                } else {
                    translationHistory.joinToString("\n\n")
                }
    }

    private fun updateStatus() {
        if (!modelManager.streamingReady()) status.text = "İlk kullanım: sağ üstteki AI HAZIR ✓ alanına dokun."
    }

    override fun onDestroy() {
        micChunker.stop(); stopPhoneIfNeeded()
        StreamingHub.setListener(null)
        streaming.stop()
        diarization.reset()
        diarization.release()
        previewTts?.stop(); previewTts?.shutdown(); previewTts = null
        translator.close()
        super.onDestroy()
    }
}
