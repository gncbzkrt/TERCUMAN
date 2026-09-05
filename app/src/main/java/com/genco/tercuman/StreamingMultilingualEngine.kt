package com.genco.tercuman

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * TERCÜMAN v1.6 multilingual streaming ASR.
 *
 * Nemotron 3.5 ASR 0.6B is a local, prompt-conditioned streaming model.
 * It supports automatic language detection and Turkish plus major European
 * and other languages from one checkpoint. The language is selected per
 * stream; v1.6 starts each stream in auto mode so the conversation can switch
 * between Turkish and the other speaker's language without a manual toggle.
 */
class StreamingMultilingualEngine(private val modelDir: File) {
    data class Result(val text: String, val endpoint: Boolean)

    private var recognizer: OnlineRecognizer? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null

    @Synchronized
    fun start(language: String = "auto") {
        stop()
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "nemo_transducer"
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 0.55f, 0.0f),
                rule2 = EndpointRule(true, 0.32f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 12.0f)
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
        recognizer = OnlineRecognizer(null, cfg)
        stream = recognizer!!.createStream().also { it.setOption("language", language) }
    }

    @Synchronized
    fun acceptPcm16(pcm: ShortArray, sampleRate: Int): Result {
        val r = recognizer ?: return Result("", false)
        val s = stream ?: return Result("", false)
        if (pcm.isEmpty()) return Result("", false)

        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        s.acceptWaveform(floats, sampleRate)

        while (r.isReady(s)) r.decode(s)

        val text = r.getResult(s).text.trim()
        val endpoint = r.isEndpoint(s)

        if (endpoint) r.reset(s)
        return Result(text, endpoint)
    }

    @Synchronized
    fun stop() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}
