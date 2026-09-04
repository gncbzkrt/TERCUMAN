package com.genco.tercuman

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicrophoneChunker(private val context: Context, private val onChunk: (ShortArray, Int) -> Unit) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    fun start() {
        if (running.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false); error("Mikrofon izni gerekli")
        }
        val sampleRate = 16000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Mikrofon buffer oluşturulamadı" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 2, sampleRate)
        )
        recorder = record
        record.startRecording()
        thread(name = "tercuman-mic-stream") {
            val buf = ShortArray(1600) // 100 ms @ 16 kHz
            try {
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) onChunk(buf.copyOf(n), sampleRate)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        recorder = null
    }
}
