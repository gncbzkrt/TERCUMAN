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
 * TERCÜMAN v1.7 multilingual streaming ASR.
 *
 * Uses the prompt-conditioned NVIDIA Nemotron 3.5 streaming model. The
 * Android wrapper does not expose a per-stream language field yet, so the
 * stream is intentionally created with the model's default empty language
 * hint, which the model treats as automatic language detection.
 * Turkish is supported by the model.
 */
class StreamingMultilingualEngine(private val modelDir: File) {
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
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu"
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
