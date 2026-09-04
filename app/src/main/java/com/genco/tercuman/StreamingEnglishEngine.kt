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
 * v1.0.3 gerçek streaming ASR.
 * İlk streaming hattı İngilizce kaynak dil içindir. Ses parçaları ayrı ayrı Whisper'a
 * gönderilmez; tek bir OnlineStream'e art arda beslenir. Endpoint geldiğinde ifade
 * tamamlanmış kabul edilir ve yeni bir OnlineStream açılır.
 */
class StreamingEnglishEngine(private val context: Context, private val modelDir: File) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var pendingPrefix = ""

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
                rule1 = EndpointRule(false, 1.2f, 0.0f),
                rule2 = EndpointRule(true, 1.0f, 0.0f),
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
    fun acceptPcm16(pcm: ShortArray, sampleRate: Int): Pair<String, Boolean> {
        val r = recognizer ?: return "" to false
        val s = stream ?: return "" to false
        if (pcm.isEmpty()) return "" to false
        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        s.acceptWaveform(floats, sampleRate)
        while (r.isReady(s)) r.decode(s)
        val result = r.getResult(s)
        val rawText = result.text.trim()
        val combined = if (pendingPrefix.isBlank()) rawText else listOf(pendingPrefix, rawText).filter { it.isNotBlank() }.joinToString(" ")
        val endpoint = r.isEndpoint(s)
        if (endpoint) {
            // If the recognizer stopped on a fragment such as "that must be very",
            // keep it locally and attach the next streaming segment before translation.
            if (SentenceStabilizer.looksIncomplete(combined)) {
                pendingPrefix = combined
                r.reset(s)
                return combined to false
            }
            pendingPrefix = ""
            r.reset(s)
            return combined to true
        }
        return combined to false
    }

    @Synchronized fun stop() {
        pendingPrefix = ""
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}
