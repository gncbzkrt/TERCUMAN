package com.genco.tercuman

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class NeuralVoiceEngine(private val manager: ModelManager) {
    data class VoiceProfile(val label: String, val sid: Int, val speed: Float)
    data class ToneProfile(
        val label: String,
        val speedMultiplier: Float,
        val steps: Int
    )

    // Supertonic 3 dokümantasyonundaki ses isimleri M1..M5 ve F1..F5'tir.
    // Sherpa-onnx voice.bin paketindeki sıra F1..F5 ardından M1..M5 olarak kullanılır.
    // Önceki sürümde bu sıra ters kabul edildiği için kadın/erkek sesler çapraz çıkıyordu.
    val profiles = listOf(
        VoiceProfile("Kadın 1 (F1)", 0, 1.02f), VoiceProfile("Kadın 2 (F2)", 1, 1.00f),
        VoiceProfile("Kadın 3 (F3)", 2, 0.98f), VoiceProfile("Kadın 4 (F4)", 3, 1.04f),
        VoiceProfile("Kadın 5 (F5)", 4, 0.96f), VoiceProfile("Erkek 1 (M1)", 5, 1.00f),
        VoiceProfile("Erkek 2 (M2)", 6, 0.98f), VoiceProfile("Erkek 3 (M3)", 7, 1.03f),
        VoiceProfile("Erkek 4 (M4)", 8, 0.96f), VoiceProfile("Erkek 5 (M5)", 9, 1.01f)
    )

    // Supertonic 3'te ayrı bir "emotion" parametresi yok; bu nedenle tonlar
    // modelin doğal sesini bozmadan hız/denoising adımıyla ritim karakteri verir.
    // Gerçek pitch/emotion kontrolü ileride farklı bir TTS modeliyle eklenebilir.
    val tones = listOf(
        ToneProfile("Doğal", 1.00f, 6),
        ToneProfile("Enerjik", 1.13f, 5),
        ToneProfile("Sakin", 0.91f, 6),
        ToneProfile("Haberci", 1.08f, 6),
        ToneProfile("Vurgulu", 0.97f, 7)
    )

    private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    private fun initIfNeeded(): OfflineTts {
        tts?.let { return it }
        check(manager.supertonicReady()) { "Doğal ses modeli yüklü değil" }
        val d = manager.supertonicDir
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$d/duration_predictor.int8.onnx",
                    textEncoder = "$d/text_encoder.int8.onnx",
                    vectorEstimator = "$d/vector_estimator.int8.onnx",
                    vocoder = "$d/vocoder.int8.onnx",
                    ttsJson = "$d/tts.json",
                    unicodeIndexer = "$d/unicode_indexer.bin",
                    voiceStyle = "$d/voice.bin"
                ),
                numThreads = 2,
                debug = false
            )
        )
        return OfflineTts(config = config).also { tts = it }
    }

    suspend fun speakTurkish(
        text: String,
        profile: VoiceProfile,
        tone: ToneProfile = tones.first()
    ) = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext
        val engine = initIfNeeded()
        val safeSid = if (engine.numSpeakers() > 0) profile.sid.coerceIn(0, engine.numSpeakers() - 1) else 0
        val safeSpeed = (profile.speed * tone.speedMultiplier).coerceIn(0.90f, 1.50f)
        val sampleRate = engine.sampleRate()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        stop()
        val localTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(min * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = localTrack
        localTrack.play()

        try {
            engine.generateWithConfigAndCallback(
                text = text.take(220),
                config = GenerationConfig(
                    sid = safeSid,
                    speed = safeSpeed,
                    numSteps = tone.steps.coerceIn(5, 8),
                    extra = mapOf("lang" to "tr")
                )
            ) { samples ->
                val current = track
                if (current !== localTrack) return@generateWithConfigAndCallback 0
                val pcm = ShortArray(samples.size) { i ->
                    (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
                }
                val written = runCatching { current.write(pcm, 0, pcm.size) }.getOrDefault(-1)
                if (written < 0) 0 else 1
            }
        } finally {
            if (track === localTrack) {
                runCatching { localTrack.stop() }
                runCatching { localTrack.release() }
                track = null
            }
        }
    }

    fun stop() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    fun release() {
        stop()
        runCatching { tts?.release() }
        tts = null
    }
}
