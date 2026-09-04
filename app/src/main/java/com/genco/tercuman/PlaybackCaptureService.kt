package com.genco.tercuman

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PlaybackCaptureService : Service() {
    companion object {
        const val ACTION_CHUNK = "com.genco.tercuman.PLAYBACK_CHUNK"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL = "tercuman_capture"
        const val STOP_ACTION = "com.genco.tercuman.STOP_CAPTURE"
    }

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Telefon sesi çevirisi", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            stopSelf(); return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tercuman)
            .setContentTitle("TERCÜMAN aktif")
            .setContentText("Telefon içi medya sesi dinleniyor")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(7, notification)

        if (running.getAndSet(true)) return START_STICKY
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        startCapture(projection!!)
        return START_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val sampleRate = 48000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, sampleRate * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        recorder = record
        record.startRecording()
        thread(name = "tercuman-playback") {
            val buf = ShortArray(4800) // 100 ms @ 48 kHz
            try {
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) StreamingHub.emit(buf.copyOf(n), sampleRate)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { recorder?.stop() }
        recorder = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
