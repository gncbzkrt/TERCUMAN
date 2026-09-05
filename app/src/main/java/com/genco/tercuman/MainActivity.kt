package com.genco.tercuman

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * TERCÜMAN v1.4.1
 *
 * Main-screen philosophy:
 * - Fixed application boundary/header.
 * - Only live conversation + Turkish translation remain visible.
 * - All controls live under the three-line menu.
 * - Source conversation grows downward: newest source line is at the bottom.
 * - Turkish translation grows upward: newest translation is at the top.
 *   The two newest lines therefore meet around the center divider.
 * - AI is never the gate for translation. Translation starts immediately;
 *   AI analyzes/merges the same sentence asynchronously.
 *
 * The data model is deliberately language-neutral so the next phase can
 * support A↔B two-way translation without rebuilding the conversation UI.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var streaming: StreamingEnglishEngine
    private lateinit var translator: TranslationEngine
    private lateinit var micChunker: MicrophoneChunker
    private lateinit var diarization: SpeakerDiarizationEngine
    private lateinit var aiCore: AIConversationEngine
    private var previewTts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var sourceScroll: ScrollView
    private lateinit var sourceHistoryContainer: LinearLayout
    private lateinit var sourceLiveText: TextView
    private lateinit var translationScroll: ScrollView
    private lateinit var translationHistoryContainer: LinearLayout
    private lateinit var menuButton: MaterialButton

    private var micOn = false
    private var phoneOn = false
    private var ready = false
    private var aiPreparing = false
    private var aiPendingEnglish = ""
    private val aiPendingLock = Any()
    private var latestSentenceId = 0L
    private val audioMutex = kotlinx.coroutines.sync.Mutex()
    private var translationTextSize = 20f
    private var pendingSentenceId: Long? = null

    /** Future-proof turn model: source/target are not hard-coded in the UI. */
    private data class ConversationTurn(
        val sentenceId: Long,
        var speaker: String,
        var sourceLanguage: String,
        var targetLanguage: String,
        var original: String,
        var translated: String = "",
        var translationRevision: Long = 0L
    )

    private val conversationTurns = linkedMapOf<Long, ConversationTurn>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateMenuState() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startPlaybackService(result.resultCode, result.data!!)
        } else {
            toast("Telefon sesi izni verilmedi.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15+ enforces edge-to-edge for targetSdk 35. Keep the app
        // edge-to-edge, but explicitly inset the fixed app header/content so
        // the logo and menu never sit underneath the status bar.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        modelManager = ModelManager(this)
        diarization = SpeakerDiarizationEngine(this, modelManager.modelRoot)
        aiCore = AIConversationEngine(this, modelManager.aiModelFile)
        translator = TranslationEngine()
        streaming = StreamingEnglishEngine(modelManager.streamingDir)
        micChunker = MicrophoneChunker(this) { pcm, rate ->
            StreamingHub.emit(pcm, rate)
        }

        val ui = buildUi()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        setContentView(ui)
        ViewCompat.requestApplyInsets(ui)
        initPreviewTts()
        requestBasePermissions()

        StreamingHub.setListener { chunk ->
            if (!ready) return@setListener
            lifecycleScope.launch(Dispatchers.Default) {
                audioMutex.withLock {
                    handleAudio(chunk.pcm, chunk.sampleRate)
                }
            }
        }

        updateMenuState()
    }

    private fun initPreviewTts() {
        previewTts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = previewTts?.setLanguage(Locale("tr", "TR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsReady = false
                }
                previewTts?.setSpeechRate(0.98f)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 27, 51))
        }

        // Fixed application boundary. No functional controls are placed here.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(8), dp(5))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_tercuman)
            contentDescription = "TERCÜMAN"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            marginEnd = dp(7)
        })

        header.addView(
            text("TERCÜMAN", 23f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        menuButton = MaterialButton(this).apply {
            text = "☰"
            textSize = 22f
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            contentDescription = "TERCÜMAN menüsü"
            setOnClickListener { showControlMenu() }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ))

        // Main content: deliberately only the two conversation surfaces.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }

        content.addView(sectionTitle("CANLI KONUŞMA"), lp(0))
        sourceScroll = historyScroll()
        sourceHistoryContainer = historyContainer()
        sourceLiveText = liveText("Konuşma bekleniyor…")
        sourceHistoryContainer.addView(sourceLiveText)
        sourceScroll.addView(sourceHistoryContainer)
        styleCard(sourceScroll, Color.rgb(20, 42, 74), Color.rgb(85, 214, 190))
        content.addView(sourceScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { bottomMargin = dp(5) })

        content.addView(sectionTitle("TÜRKÇE TERCÜME"), lp(0))
        translationScroll = historyScroll()
        translationHistoryContainer = historyContainer()
        translationScroll.addView(translationHistoryContainer)
        styleCard(translationScroll, Color.rgb(20, 42, 74), Color.rgb(85, 214, 190))
        content.addView(translationScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        return root
    }

    private fun sectionTitle(title: String): TextView = text(title, 18f).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }

    private fun historyScroll(): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = true
        setPadding(0, 0, 0, 0)
    }

    private fun historyContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun liveText(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 19f
        setTextColor(Color.WHITE)
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(0, dp(7), 0, dp(7))
        minHeight = dp(48)
    }

    private fun styleCard(view: View, background: Int, stroke: Int) {
        // ScrollView cannot directly draw MaterialCardView styling; use a background
        // drawable-like solid surface here to keep the main screen light and fast.
        view.setBackgroundColor(background)
        view.elevation = dp(1).toFloat()
    }

    private fun turnView(turn: ConversationTurn, translated: Boolean): TextView {
        val value = if (translated) turn.translated else turn.original
        val language = if (translated) turn.targetLanguage.uppercase() else turn.sourceLanguage.uppercase()
        return TextView(this).apply {
            text = "${turn.speaker} • $language\n$value"
            textSize = if (translated) translationTextSize else 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            setLineSpacing(0f, 1.08f)
        }
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun showControlMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(20, 42, 74))
        }

        fun menuButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
            text = label
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                action()
            }
        }

        val ai = menuButton(if (ready) "🤖 AI HAZIR ✓" else "🤖 AI HAZIRLA") {
            if (!ready) downloadModels()
            popup?.dismiss()
        }
        ai.isEnabled = !aiPreparing
        panel.addView(ai, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })

        val mic = menuButton(if (micOn) "⏹ DIŞ SESİ DURDUR" else "🎤 DIŞ SES") {
            toggleMic()
            popup?.dismiss()
        }
        panel.addView(mic, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })

        val phone = menuButton(if (phoneOn) "⏹ TELEFON SESİNİ DURDUR" else "📱 TELEFON SESİ") {
            togglePhone()
            popup?.dismiss()
        }
        panel.addView(phone, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })

        val clear = menuButton("🧹 EKRANI TEMİZLE") {
            clearConversation()
            popup?.dismiss()
        }
        panel.addView(clear, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })

        val speak = Switch(this).apply {
            text = "Türkçeyi seslendir"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = speakEnabled
            setPadding(dp(8), 0, dp(8), 0)
        }
        panel.addView(speak, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })
        speak.setOnCheckedChangeListener { _, checked ->
            speakEnabled = checked
        }

        val preview = menuButton("▶ SES ÖNİZLEME") {
            previewSpeech()
            popup?.dismiss()
        }
        panel.addView(preview, LinearLayout.LayoutParams(dp(230), dp(46)).apply { bottomMargin = dp(3) })

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(text("Yazı boyutu", 13f), LinearLayout.LayoutParams(0, dp(44), 1f))
        val minus = menuButton("−") { setTranslationTextSize(translationTextSize - 2f) }
        val size = text(translationTextSize.toInt().toString(), 14f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
        }
        val plus = menuButton("+") { setTranslationTextSize(translationTextSize + 2f) }
        sizeRow.addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
        sizeRow.addView(size, LinearLayout.LayoutParams(dp(34), dp(44)))
        sizeRow.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(sizeRow)

        val languageInfo = text(
            "🌐 Dil: OTOMATİK • çift yönlü mimari hazır\nİlk akış: İngilizce → Türkçe",
            11f,
            Color.rgb(170, 184, 197)
        ).apply {
            setPadding(dp(8), dp(8), dp(8), dp(5))
        }
        panel.addView(languageInfo, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))

        popup = PopupWindow(
            panel,
            dp(246),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(20, 42, 74)))
            isOutsideTouchable = true
        }
        popup?.showAsDropDown(menuButton, -dp(198), dp(2), Gravity.END)
    }

    private var popup: PopupWindow? = null
    private var speakEnabled = false

    private fun setTranslationTextSize(newSize: Float) {
        translationTextSize = newSize.coerceIn(14f, 24f)
        for (i in 0 until translationHistoryContainer.childCount) {
            translationHistoryContainer.getChildAt(i).let { if (it is TextView) it.textSize = translationTextSize }
        }
    }

    private fun requestBasePermissions() {
        val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(req.toTypedArray())
    }

    private fun previewSpeech() {
        if (!ttsReady) {
            toast("Telefonun Türkçe konuşma motoru hazır değil.")
            return
        }
        previewTts?.setSpeechRate(0.98f)
        previewTts?.speak(
            "Merhaba. Ben Tercüman. Yabancı konuşmaları Türkçeye çevirmeye hazırım.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "tercuman_preview"
        )
    }

    private fun downloadModels() {
        if (aiPreparing) return
        aiPreparing = true
        updateMenuState()

        // Model initialization is deliberately off the main thread.
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                modelManager.ensureAiCore { _ -> }
                aiCore.start()

                modelManager.ensureDiarization { _ -> }
                diarization.start()

                modelManager.ensureStreamingEnglish { _ -> }
                translator.prepareEnglishTurkish { _ -> }
                streaming.start()

                ready = true
                withContext(Dispatchers.Main) {
                    toast("AI CORE ✓ • STREAMING ✓ • EN → TR ✓")
                }
            } catch (e: Exception) {
                ready = false
                withContext(Dispatchers.Main) {
                    toast("Hazırlık hatası: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                aiPreparing = false
                updateMenuState()
            }
        }
    }

    private fun toggleMic() {
        if (!ready) {
            toast("Önce ☰ menüsünden AI HAZIRLA.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestBasePermissions()
            return
        }

        if (micOn) {
            micChunker.stop()
            micOn = false
            toast("Dış ses durduruldu.")
        } else {
            stopPhoneIfNeeded()
            try {
                micChunker.start()
                micOn = true
                toast("Dış ses canlı dinleniyor…")
            } catch (e: Exception) {
                toast(e.message ?: "Mikrofon başlatılamadı.")
            }
        }
    }

    private fun togglePhone() {
        if (!ready) {
            toast("Önce ☰ menüsünden AI HAZIRLA.")
            return
        }

        if (phoneOn) {
            stopPhoneIfNeeded()
        } else {
            micChunker.stop()
            micOn = false
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun startPlaybackService(code: Int, data: Intent) {
        val i = Intent(this, PlaybackCaptureService::class.java)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, code)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        phoneOn = true
        toast("Telefon sesi canlı dinleniyor…")
    }

    private fun stopPhoneIfNeeded() {
        if (!phoneOn) return
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.STOP_ACTION))
        phoneOn = false
        toast("Telefon sesi durduruldu.")
    }

    private var lastPartialShownAt = 0L
    private var lastPartialShown = ""

    private suspend fun handleAudio(pcm: ShortArray, sampleRate: Int) {
        try {
            diarization.addPcm16(pcm, sampleRate)

            val result = streaming.acceptPcm16(pcm, sampleRate)
            val partial = result.text.trim()
            val endpoint = result.endpoint

            if (partial.isBlank()) return

            if (!endpoint) {
                val now = System.currentTimeMillis()
                if (partial != lastPartialShown && now - lastPartialShownAt >= 120L) {
                    lastPartialShown = partial
                    lastPartialShownAt = now
                    withContext(Dispatchers.Main) {
                        sourceLiveText.text = partial
                        sourceScroll.post { sourceScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                return
            }

            val candidate = normalize(partial)
            if (candidate.isBlank()) return

            /*
             * FAST TRANSLATION PATH:
             * A sentence gets a visible identity immediately. Translation starts
             * now; AI analysis runs beside it and may merge/correct this same turn.
             */
            val pendingBefore = synchronized(aiPendingLock) { aiPendingEnglish }
            val combined = listOf(pendingBefore, candidate)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()

            val sentenceId = pendingSentenceId ?: (++latestSentenceId)
            pendingSentenceId = sentenceId
            synchronized(aiPendingLock) { aiPendingEnglish = combined }

            withContext(Dispatchers.Main) {
                upsertTurn(
                    sentenceId = sentenceId,
                    original = combined,
                    translated = "",
                    sourceLanguage = "en",
                    targetLanguage = "tr"
                )
                sourceLiveText.text = ""
                sourceScroll.post { sourceScroll.fullScroll(View.FOCUS_DOWN) }
            }

            // Translation is intentionally independent of AI.
            scheduleTranslation(combined, sentenceId)

            // AI sentence-boundary analysis is intentionally independent as well.
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val decision = aiCore.decide(
                        candidate = candidate,
                        previousPending = pendingBefore
                    )

                    when (decision.action) {
                        AIConversationEngine.Action.WAIT -> {
                            synchronized(aiPendingLock) { aiPendingEnglish = combined }
                        }

                        AIConversationEngine.Action.COMMIT -> {
                            val stable = normalize(decision.sentence.ifBlank { combined })
                            if (stable.isBlank()) return@launch

                            synchronized(aiPendingLock) { aiPendingEnglish = "" }
                            pendingSentenceId = null

                            val current = conversationTurns[sentenceId]
                            if (current != null && current.original != stable) {
                                current.original = stable
                                current.translationRevision++
                                current.translated = ""
                                withContext(Dispatchers.Main) {
                                    renderSourceHistory()
                                }
                                scheduleTranslation(stable, sentenceId)
                            }

                            lifecycleScope.launch(Dispatchers.Default) {
                                runCatching {
                                    val speaker = diarization.currentSpeaker()
                                    withContext(Dispatchers.Main) {
                                        conversationTurns[sentenceId]?.speaker = speaker
                                        renderSourceHistory()
                                        renderTranslationHistory()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Translation must continue even if local AI is unavailable.
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                toast("Streaming ASR hatası: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleTranslation(text: String, sentenceId: Long) {
        val revision = conversationTurns[sentenceId]?.translationRevision ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val translated = translator.translateEnglishToTurkish(text).trim()
                if (translated.isBlank()) return@launch

                withContext(Dispatchers.Main) {
                    val turn = conversationTurns[sentenceId] ?: return@withContext
                    // Ignore an older translation that finished after an AI merge/correction.
                    if (turn.translationRevision != revision || turn.original != text) return@withContext
                    turn.translated = translated
                    renderTranslationHistory()

                    if (speakEnabled && ttsReady) {
                        previewTts?.speak(
                            translated,
                            TextToSpeech.QUEUE_ADD,
                            null,
                            "final_${sentenceId}_${System.currentTimeMillis()}"
                        )
                    }
                }
            } catch (_: Exception) {
                // A later AI merge can retry the same sentence without blocking the UI.
            }
        }
    }

    private fun upsertTurn(
        sentenceId: Long,
        original: String,
        translated: String,
        sourceLanguage: String,
        targetLanguage: String
    ) {
        val turn = conversationTurns[sentenceId]
        if (turn == null) {
            conversationTurns[sentenceId] = ConversationTurn(
                sentenceId = sentenceId,
                speaker = "A",
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                original = original,
                translated = translated
            )
        } else {
            val originalChanged = turn.original != original
            turn.original = original
            turn.sourceLanguage = sourceLanguage
            turn.targetLanguage = targetLanguage
            if (originalChanged) {
                turn.translationRevision++
                turn.translated = ""
            }
            if (translated.isNotBlank()) turn.translated = translated
        }
        renderSourceHistory()
        renderTranslationHistory()
    }

    /** Source: oldest at top, newest at bottom. */
    private fun renderSourceHistory() {
        sourceHistoryContainer.removeAllViews()
        conversationTurns.values
            .sortedBy { it.sentenceId }
            .forEach { turn ->
                sourceHistoryContainer.addView(turnView(turn, translated = false))
            }
        sourceHistoryContainer.addView(sourceLiveText)
        sourceScroll.post { sourceScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Turkish: newest at top, older translations pushed downward. */
    private fun renderTranslationHistory() {
        translationHistoryContainer.removeAllViews()
        conversationTurns.values
            .sortedByDescending { it.sentenceId }
            .filter { it.translated.isNotBlank() }
            .forEach { turn ->
                translationHistoryContainer.addView(turnView(turn, translated = true))
            }
        translationScroll.post { translationScroll.scrollTo(0, 0) }
    }

    private fun clearConversation() {
        synchronized(aiPendingLock) { aiPendingEnglish = "" }
        pendingSentenceId = null
        latestSentenceId = 0L
        lastPartialShown = ""
        lastPartialShownAt = 0L
        conversationTurns.clear()
        sourceHistoryContainer.removeAllViews()
        sourceHistoryContainer.addView(sourceLiveText)
        sourceLiveText.text = "Konuşma bekleniyor…"
        translationHistoryContainer.removeAllViews()
        sourceScroll.scrollTo(0, 0)
        translationScroll.scrollTo(0, 0)
        toast("Ekran temizlendi. Yeni konuya hazırsın.")
    }

    private fun updateMenuState() {
        menuButton.alpha = if (aiPreparing) 0.6f else 1f
    }

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        popup?.dismiss()
        micChunker.stop()
        stopPhoneIfNeeded()
        StreamingHub.setListener(null)
        streaming.stop()
        diarization.reset()
        diarization.release()
        previewTts?.stop()
        previewTts?.shutdown()
        previewTts = null
        translator.close()
        aiCore.close()
        super.onDestroy()
    }
}
