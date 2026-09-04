package com.genco.tercuman

/** Same-process low-latency audio bus for microphone and playback capture. */
object StreamingHub {
    data class AudioChunk(val pcm: ShortArray, val sampleRate: Int)
    @Volatile private var listener: ((AudioChunk) -> Unit)? = null

    fun setListener(block: ((AudioChunk) -> Unit)?) { listener = block }
    fun emit(pcm: ShortArray, sampleRate: Int) { listener?.invoke(AudioChunk(pcm, sampleRate)) }
}
