package com.genco.tercuman

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local speaker diarization for short conversation windows.
 *
 * It does not run on every PCM chunk. The caller feeds completed
 * utterance audio into the rolling buffer and asks for the current
 * speaker only when an utterance is ready.
 */
class SpeakerDiarizationEngine(
    private val context: Context,
    private val modelDir: File
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MAX_SECONDS = 10
        private const val MIN_SECONDS = 0.9
    }

    private var diarizer: OfflineSpeakerDiarization? = null

    // Fixed-size circular buffer: avoids O(n) removeAt(0) shifting on every chunk.
    private val maxSamples = SAMPLE_RATE * MAX_SECONDS
    private val pcmBuffer = FloatArray(maxSamples)
    private var pcmWriteIndex = 0
    private var pcmSize = 0
    private var bufferStartSeconds = 0.0
    private var totalSeconds = 0.0

    private data class LabeledSegment(
        val start: Double,
        val end: Double,
        val speaker: String
    )

    private var previousSegments = emptyList<LabeledSegment>()
    private var previousSpeaker = "A"

    /* Heavy speaker analysis is serialized separately from audio buffering. */
    private val analysisLock = Any()

    fun start() {
        release()

        val segmentation = File(
            modelDir,
            "sherpa-onnx-pyannote-segmentation-3-0/model.onnx"
        )

        val embedding = File(
            modelDir,
            "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        )

        if (!segmentation.exists() || !embedding.exists()) {
            throw IllegalStateException(
                "Konuşmacı modeli hazır değil."
            )
        }

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    segmentation.absolutePath
                ),
                numThreads = 2,
                provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embedding.absolutePath,
                numThreads = 2,
                provider = "cpu"
            ),
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = 0.5f
            ),
            minDurationOn = 0.20f,
            minDurationOff = 0.50f
        )

        diarizer = OfflineSpeakerDiarization(
            assetManager = null,
            config = config
        )
    }

    @Synchronized
    fun addPcm16(pcm: ShortArray, sampleRate: Int) {
        if (pcm.isEmpty()) return

        val samples = resampleTo16k(pcm, sampleRate)

        for (sample in samples) {
            pcmBuffer[pcmWriteIndex] = sample
            pcmWriteIndex = (pcmWriteIndex + 1) % maxSamples
            if (pcmSize < maxSamples) pcmSize++
        }

        totalSeconds += pcm.size.toDouble() / max(sampleRate, 1)
        bufferStartSeconds = totalSeconds - pcmSize.toDouble() / SAMPLE_RATE
    }

    /**
     * Analyze the current rolling window and return the speaker
     * associated with the most recent speech segment.
     */
    /**
     * Analyze the latest rolling audio window.
     *
     * IMPORTANT:
     * The audio buffer is locked only long enough to copy a snapshot.
     * The expensive engine.process() call happens OUTSIDE the audio
     * buffer lock, so incoming ASR audio can continue uninterrupted.
     */
    fun currentSpeaker(): String {
        val engine = diarizer ?: return previousSpeaker

        val samples: FloatArray
        val snapshotStartSeconds: Double

        /*
         * Take a very short snapshot of the rolling audio buffer.
         * No heavy ML work is allowed inside this synchronized block.
         */
        synchronized(this) {
            if (pcmSize < (SAMPLE_RATE * MIN_SECONDS).toInt()) {
                return previousSpeaker
            }

            samples = FloatArray(pcmSize)
            val start = if (pcmSize == maxSamples) pcmWriteIndex else 0
            for (i in 0 until pcmSize) {
                samples[i] = pcmBuffer[(start + i) % maxSamples]
            }

            snapshotStartSeconds = bufferStartSeconds
        }

        /*
         * Serialize expensive diarization calculations, but use a
         * DIFFERENT lock from the real-time audio buffer lock.
         */
        return synchronized(analysisLock) {
            try {
                val segments = engine.process(samples)
                    .filter { it.end > it.start }
                    .sortedBy { it.start }

                if (segments.isEmpty()) {
                    return@synchronized previousSpeaker
                }

                val current = segments.map {
                    LabeledSegment(
                        start = snapshotStartSeconds + it.start,
                        end = snapshotStartSeconds + it.end,
                        speaker = "C${it.speaker}"
                    )
                }

                /*
                 * previousSegments / previousSpeaker are shared state.
                 * Update them only after the expensive processing has
                 * completed.
                 */
                synchronized(this) {
                    val mapping = buildMapping(current, previousSegments)

                    val latest = current.maxByOrNull { it.end }
                        ?: return@synchronized previousSpeaker

                    val mapped = mapping[latest.speaker]

                    val result =
                        mapped ?: if (previousSpeaker == "A") "B" else "A"

                    previousSegments = current.map {
                        it.copy(
                            speaker = mapping[it.speaker] ?: previousSpeaker
                        )
                    }

                    previousSpeaker = result
                    result
                }

            } catch (_: Throwable) {
                synchronized(this) {
                    previousSpeaker
                }
            }
        }
    }

    private fun buildMapping(
        current: List<LabeledSegment>,
        previous: List<LabeledSegment>
    ): Map<String, String> {
        val clusters = current.map { it.speaker }.distinct()
        if (clusters.isEmpty()) return emptyMap()

        val scores = mutableMapOf<String, MutableMap<String, Double>>()

        for (cluster in clusters) {
            val map = mutableMapOf<String, Double>()

            for (old in previous) {
                if (old.speaker != "A" && old.speaker != "B") continue

                for (now in current) {
                    if (now.speaker != cluster) continue

                    val overlap = max(
                        0.0,
                        min(now.end, old.end) - max(now.start, old.start)
                    )

                    if (overlap > 0.0) {
                        map[old.speaker] =
                            (map[old.speaker] ?: 0.0) + overlap
                    }
                }
            }

            scores[cluster] = map
        }

        val result = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()

        for (cluster in clusters) {
            val best = scores[cluster]
                ?.filterKeys { it !in used }
                ?.maxByOrNull { it.value }

            if (best != null && best.value > 0.05) {
                result[cluster] = best.key
                used += best.key
            }
        }

        for (cluster in clusters) {
            if (cluster in result) continue

            val candidate =
                if ("A" !in used) "A"
                else if ("B" !in used) "B"
                else previousSpeaker

            result[cluster] = candidate
            used += candidate
        }

        return result
    }

    private fun resampleTo16k(
        pcm: ShortArray,
        inputRate: Int
    ): FloatArray {
        if (inputRate <= 0) return FloatArray(0)

        if (inputRate == SAMPLE_RATE) {
            return FloatArray(pcm.size) { i ->
                pcm[i].toFloat() / 32768f
            }
        }

        val outputSize =
            (pcm.size.toLong() * SAMPLE_RATE / inputRate).toInt()

        if (outputSize <= 0) return FloatArray(0)

        val out = FloatArray(outputSize)

        for (i in out.indices) {
            val position =
                i.toDouble() * (pcm.size - 1).toDouble() /
                    max(outputSize - 1, 1)

            val left = position.toInt()
            val right = min(left + 1, pcm.lastIndex)
            val fraction = position - left

            val value =
                pcm[left] * (1.0 - fraction) +
                    pcm[right] * fraction

            out[i] = (value / 32768.0).toFloat()
        }

        return out
    }

    @Synchronized
    fun reset() {
        pcmWriteIndex = 0
        pcmSize = 0
        bufferStartSeconds = 0.0
        totalSeconds = 0.0
        previousSegments = emptyList()
        previousSpeaker = "A"
    }

    @Synchronized
    fun release() {
        diarizer?.release()
        diarizer = null
    }
}
