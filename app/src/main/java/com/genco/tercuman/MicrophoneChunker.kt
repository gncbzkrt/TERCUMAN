package com.genco.tercuman

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MicrophoneChunker(private val context: Context, private val onChunk: (File) -> Unit) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    fun start() {
        if (running.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            error("Mikrofon izni gerekli")
        }

        val sampleRate = 16000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Mikrofon ses formatı desteklenmiyor." }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 2, sampleRate * 2)
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon başlatılamadı." }
        recorder = record
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        record.startRecording()

        thread(name = "tercuman-mic") {
            // VAD tabanlı kısa parçalar: konuşma bittiğinde hemen, uzun konuşmada
            // en fazla ~1 sn'de bir parça gönderilir. Sabit 1.2 sn bekleme yok.
            val frameSamples = sampleRate / 50 // 20 ms
            val maxSamples = (sampleRate * 1.0f).toInt()
            val minSpeechSamples = (sampleRate * 0.35f).toInt()
            val silenceFramesToFlush = 13 // ~260 ms
            val buf = ShortArray(frameSamples)
            val speech = ArrayList<Short>(maxSamples)
            var speechStarted = false
            var silenceFrames = 0
            var lastEnergy = 0.0

            fun flush() {
                if (!speechStarted || speech.size < minSpeechSamples) {
                    speech.clear(); speechStarted = false; silenceFrames = 0; return
                }
                val data = ShortArray(speech.size) { speech[it] }
                speech.clear(); speechStarted = false; silenceFrames = 0
                if (rms(data) < 350.0) return
                val file = File(context.cacheDir, "mic_${System.currentTimeMillis()}.wav")
                WavUtils.writePcm16Mono(file, data, sampleRate)
                onChunk(file)
            }

            try {
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val frame = if (n == buf.size) buf else buf.copyOf(n)
                    lastEnergy = rms(frame)
                    val speechFrame = lastEnergy >= 420.0

                    if (speechFrame) {
                        speechStarted = true
                        silenceFrames = 0
                    } else if (speechStarted) {
                        silenceFrames++
                    }

                    if (speechStarted) {
                        for (s in frame) speech.add(s)
                    }

                    if (speechStarted && (speech.size >= maxSamples || silenceFrames >= silenceFramesToFlush)) flush()
                }
            } finally {
                flush()
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    private fun rms(data: ShortArray): Double {
        var sum = 0.0
        for (s in data) { val v = s.toDouble(); sum += v * v }
        return sqrt(sum / data.size.coerceAtLeast(1))
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        recorder = null
    }
}
