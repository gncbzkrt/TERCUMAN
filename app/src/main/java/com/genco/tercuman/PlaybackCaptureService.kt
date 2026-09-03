package com.genco.tercuman

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class PlaybackCaptureService : Service() {
    companion object {
        const val ACTION_CHUNK = "com.genco.tercuman.PLAYBACK_CHUNK"
        const val ACTION_STATUS = "com.genco.tercuman.PLAYBACK_STATUS"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_STATUS = "status"
        const val CHANNEL = "tercuman_capture"
        const val STOP_ACTION = "com.genco.tercuman.STOP_CAPTURE"
    }

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            running.set(false)
            broadcastStatus("Android ses yakalama oturumu sona erdi.")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Telefon sesi çevirisi", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) { running.set(false); stopSelf(); return START_NOT_STICKY }

        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tercuman)
            .setContentTitle("TERCÜMAN aktif")
            .setContentText("Telefon içi medya sesi dinleniyor")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)
        if (running.getAndSet(true)) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) { running.set(false); stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(projectionCallback, null)
        try {
            startCapture(projection ?: error("MediaProjection başlatılamadı"))
            broadcastStatus("Telefon ses akışı bağlandı. Kaynak sesi bekleniyor…")
        } catch (e: Exception) {
            broadcastStatus("Telefon sesi yakalanamadı: ${e.message ?: "bilinmeyen hata"}")
            running.set(false); stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }

    private fun startCapture(mp: MediaProjection) {
        // v0.6: v0.2'de çalışan 48 kHz + 2 saniyelik playback hattını geri getiriyoruz.
        val sampleRate = 48000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Telefon sesi için ses formatı desteklenmiyor." }

        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(applicationInfo.uid)
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
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Telefon sesi yakalama başlatılamadı." }
        recorder = record
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Telefon sesi kayıt durumuna geçemedi." }

        thread(name = "tercuman-playback") {
            val chunkSamples = sampleRate * 2
            val buf = ShortArray(4096)
            val collected = ArrayList<Short>(chunkSamples)
            var silentSince = System.currentTimeMillis()
            var sentSilentWarning = false
            try {
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) collected.add(buf[i])
                    if (collected.size >= chunkSamples) {
                        val pcm = ShortArray(chunkSamples) { collected[it] }
                        collected.clear()
                        val energy = rms(pcm)
                        if (energy < 180.0) {
                            if (System.currentTimeMillis() - silentSince > 2500 && !sentSilentWarning) {
                                broadcastStatus("Telefon ses akışı sessiz. YouTube/Instagram kaynağı yakalamaya izin vermiyor olabilir.")
                                sentSilentWarning = true
                            }
                            continue
                        }
                        silentSince = System.currentTimeMillis()
                        sentSilentWarning = false
                        val f = File(cacheDir, "phone_${System.currentTimeMillis()}.wav")
                        WavUtils.writePcm16Mono(f, pcm, sampleRate)
                        sendBroadcast(Intent(ACTION_CHUNK).setPackage(packageName).putExtra(EXTRA_PATH, f.absolutePath))
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    private fun rms(data: ShortArray): Double {
        var sum = 0.0
        for (s in data) { val v = s.toDouble(); sum += v * v }
        return kotlin.math.sqrt(sum / data.size.coerceAtLeast(1))
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { recorder?.stop() }
        recorder = null
        projection?.let { mp -> runCatching { mp.unregisterCallback(projectionCallback) } }
        projection?.stop(); projection = null
        super.onDestroy()
    }
}
