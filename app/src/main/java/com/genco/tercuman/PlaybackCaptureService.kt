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
        val candidates = listOf(48000, 44100, 16000)
        var record: AudioRecord? = null
        var selectedRate = 0
        var selectedMin = 0

        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(applicationInfo.uid)
            .build()

        for (rate in candidates) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val candidate = runCatching {
                val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(min * 3, rate / 2))
                    .setAudioPlaybackCaptureConfig(config).build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            if (candidate != null) { record = candidate; selectedRate = rate; selectedMin = min; break }
        }

        check(record != null) { "Bu cihazda playback capture AudioRecord oluşturulamadı." }
        recorder = record
        val active = record
        active.startRecording()
        check(active.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Playback capture kayıt durumuna geçemedi." }

        thread(name = "tercuman-playback") {
            // Playback için de VAD: kısa sessizlikte hemen flush, sürekli seste ~1 sn üst sınır.
            val frameSamples = maxOf(160, selectedRate / 50)
            val maxSamples = selectedRate
            val minSpeechSamples = (selectedRate * 0.35f).toInt()
            val silenceFramesToFlush = 13
            val buf = ShortArray(maxOf(2048, selectedMin / 4))
            val speech = ArrayList<Short>(maxSamples)
            var speechStarted = false
            var silenceFrames = 0
            var silentSince = System.currentTimeMillis()
            var sentSilentWarning = false

            fun flush() {
                if (!speechStarted || speech.size < minSpeechSamples) {
                    speech.clear(); speechStarted = false; silenceFrames = 0; return
                }
                val pcm = ShortArray(speech.size) { speech[it] }
                speech.clear(); speechStarted = false; silenceFrames = 0
                if (rms(pcm) < 220.0) return
                val f = File(cacheDir, "phone_${System.currentTimeMillis()}.wav")
                WavUtils.writePcm16Mono(f, pcm, selectedRate)
                sendBroadcast(Intent(ACTION_CHUNK).setPackage(packageName).putExtra(EXTRA_PATH, f.absolutePath))
            }

            try {
                while (running.get()) {
                    val n = active.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var offset = 0
                    while (offset < n) {
                        val take = minOf(frameSamples, n - offset)
                        val frame = ShortArray(take)
                        System.arraycopy(buf, offset, frame, 0, take)
                        offset += take
                        val energy = rms(frame)
                        val speechFrame = energy >= 210.0
                        if (speechFrame) { speechStarted = true; silenceFrames = 0; silentSince = System.currentTimeMillis(); sentSilentWarning = false }
                        else if (speechStarted) silenceFrames++
                        else if (System.currentTimeMillis() - silentSince > 2500 && !sentSilentWarning) {
                            broadcastStatus("Ses akışı sessiz. YouTube/Instagram kaydı Android veya kaynak uygulama tarafından engelleniyor olabilir.")
                            sentSilentWarning = true
                        }
                        if (speechStarted) for (s in frame) speech.add(s)
                        if (speechStarted && (speech.size >= maxSamples || silenceFrames >= silenceFramesToFlush)) flush()
                    }
                }
            } finally {
                flush()
                runCatching { active.stop() }
                active.release()
            }
        }
    }

    private fun rms(data: ShortArray): Double {
        var sum = 0.0
        for (s in data) { val v = s.toDouble(); sum += v * v }
        return sqrt(sum / data.size.coerceAtLeast(1))
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
