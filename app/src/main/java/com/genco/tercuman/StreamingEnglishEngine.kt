package com.genco.tercuman

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class StreamingEnglishEngine(private val modelDir: File) {

    data class Result(
        val text: String,
        val endpoint: Boolean
    )

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /*
     * Some streaming models occasionally stop on a fragment.
     * Keep that fragment locally and attach it to the next segment.
     */
    private var pendingPrefix = ""

    @Synchronized
    fun start() {
        stop()

        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),

            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(
                        modelDir,
                        "encoder-epoch-99-avg-1.int8.onnx"
                    ).absolutePath,

                    decoder = File(
                        modelDir,
                        "decoder-epoch-99-avg-1.onnx"
                    ).absolutePath,

                    joiner = File(
                        modelDir,
                        "joiner-epoch-99-avg-1.int8.onnx"
                    ).absolutePath
                ),

                tokens = File(
                    modelDir,
                    "tokens.txt"
                ).absolutePath,

                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer"
            ),

            /*
             * Lower endpoint latency.
             *
             * rule1:
             * endpoint after a short trailing silence.
             *
             * rule2:
             * stronger endpoint rule for decoded speech.
             *
             * rule3:
             * safety limit for an unusually long utterance.
             */
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    false,
                    0.65f,
                    0.0f
                ),

                rule2 = EndpointRule(
                    true,
                    0.55f,
                    0.0f
                ),

                rule3 = EndpointRule(
                    false,
                    0.0f,
                    15.0f
                )
            ),

            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )

        recognizer = OnlineRecognizer(null, cfg)
        stream = recognizer!!.createStream()
        pendingPrefix = ""
    }

    /**
     * Accept a PCM16 chunk and return:
     *
     * text     = current streaming text
     * endpoint = whether the recognizer considers the utterance complete
     *
     * Partial text is intentionally returned immediately.
     * MainActivity decides whether/how often to display it.
     */
    @Synchronized
    fun acceptPcm16(
        pcm: ShortArray,
        sampleRate: Int
    ): Result {

        val r = recognizer ?: return Result("", false)
        val s = stream ?: return Result("", false)

        if (pcm.isEmpty()) {
            return Result("", false)
        }

        val floats = FloatArray(pcm.size) {
            pcm[it] / 32768.0f
        }

        s.acceptWaveform(
            floats,
            sampleRate
        )

        while (r.isReady(s)) {
            r.decode(s)
        }

        val rawText = r
            .getResult(s)
            .text
            .trim()

        val combined =
            if (pendingPrefix.isBlank()) {
                rawText
            } else {
                listOf(
                    pendingPrefix,
                    rawText
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }

        val endpoint = r.isEndpoint(s)

        if (endpoint) {

            /*
             * Do not immediately translate tiny fragments.
             * Keep incomplete fragments for the next streaming segment.
             */
            if (!SentenceStabilizer.looksIncomplete(combined)) {
                pendingPrefix = ""
                r.reset(s)

                return Result(
                    combined.trim(),
                    true
                )
            }

            pendingPrefix = combined.trim()
            r.reset(s)

            return Result(
                pendingPrefix,
                false
            )
        }

        return Result(
            combined,
            false
        )
    }

    @Synchronized
    fun stop() {
        pendingPrefix = ""

        stream?.release()
        stream = null

        recognizer?.release()
        recognizer = null
    }
}
