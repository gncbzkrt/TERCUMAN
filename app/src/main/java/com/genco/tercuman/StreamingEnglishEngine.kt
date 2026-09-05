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
 * TERCÜMAN v1.4.1 streaming ASR.
 *
 * Endpoint detection only tells the AI layer that a speech pause occurred.
 * It no longer makes linguistic sentence decisions.
 */
class StreamingEnglishEngine(private val modelDir: File) {
    data class Result(val text: String, val endpoint: Boolean)

    private var recognizer: OnlineRecognizer? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null

    @Synchronized
    fun start() {
        stop()
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer"
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
        stream = recognizer!!.createStream()
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
