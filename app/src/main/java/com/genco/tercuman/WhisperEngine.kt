package com.genco.tercuman

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class WhisperEngine(private val context: Context, private val manager: ModelManager) {
    private val mutex = Mutex()
    private var model: WhisperModel? = null

    suspend fun transcribe(wav: File): String = mutex.withLock {
        if (!manager.whisperReady()) error("Whisper modeli yüklü değil")
        val active = model ?: Whisper.loadModel(context, manager.whisperFile.absolutePath).also { model = it }
        val result = Whisper.transcribe(active, wav.absolutePath, WhisperConfig())
        result.text.trim()
    }

    fun release() {
        model?.let { Whisper.releaseModel(it) }
        model = null
    }
}
