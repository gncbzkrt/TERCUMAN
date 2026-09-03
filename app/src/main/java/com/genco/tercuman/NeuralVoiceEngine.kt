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

    // Supertonic 3 toplam 10 sabit ses stili sunar: M1-M5 ve F1-F5.
    // Sherpa paketindeki speaker dizilimi upstream model sırasını izler: M1..M5, F1..F5.
    // Arayüzde kullanıcı isteğine uygun olarak kadın sesleri önce gösterilir.
    val profiles = listOf(
        VoiceProfile("Kadın 1 (F1)", 5, 1.02f), VoiceProfile("Kadın 2 (F2)", 6, 1.00f),
        VoiceProfile("Kadın 3 (F3)", 7, 0.98f), VoiceProfile("Kadın 4 (F4)", 8, 1.04f),
        VoiceProfile("Kadın 5 (F5)", 9, 0.96f), VoiceProfile("Erkek 1 (M1)", 0, 1.00f),
        VoiceProfile("Erkek 2 (M2)", 1, 0.98f), VoiceProfile("Erkek 3 (M3)", 2, 1.03f),
        VoiceProfile("Erkek 4 (M4)", 3, 0.96f), VoiceProfile("Erkek 5 (M5)", 4, 1.01f)
    )

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

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

    suspend fun speakTurkish(text: String, profile: VoiceProfile) = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext
        val engine = initIfNeeded()
        val safeSid = if (engine.numSpeakers() > 0) profile.sid.coerceIn(0, engine.numSpeakers() - 1) else 0
        val generated = engine.generateWithConfig(
            text = text.take(450),
            config = GenerationConfig(
                sid = safeSid,
                speed = profile.speed,
                numSteps = 6,
                extra = mapOf("lang" to "tr")
            )
        )
        playFloatPcm(generated.samples, generated.sampleRate)
    }

    private fun playFloatPcm(samples: FloatArray, sampleRate: Int) {
        stop()
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(min, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track?.write(pcm, 0, pcm.size)
        track?.play()
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
