package com.genco.tercuman

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * v1.0 deneysel gerçek streaming ASR.
 * İlk deney İngilizce kaynak dil içindir. Ses parçaları ayrı ayrı Whisper'a
 * gönderilmez; tek bir OnlineStream'e art arda beslenir.
 */
class StreamingEnglishEngine(private val context: Context, private val modelDir: File) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    fun start() {
        stop()
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 0.9f, 0.0f),
                rule2 = EndpointRule(true, 0.7f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(null, cfg)
        stream = recognizer!!.createStream()
    }

    /** Returns current partial/final English text after accepting a PCM chunk. */
    @Synchronized
    fun acceptPcm16(pcm: ShortArray, sampleRate: Int): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        if (pcm.isEmpty()) return ""
        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        s.acceptWaveform(floats, sampleRate)
        while (r.isReady(s)) r.decode(s)
        val result = r.getResult(s)
        val text = result.text.trim()
        if (r.isEndpoint(s)) {
            // Finalize this utterance and immediately create a fresh stream.
            r.reset(s)
        }
        return text
    }

    @Synchronized fun stop() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}
