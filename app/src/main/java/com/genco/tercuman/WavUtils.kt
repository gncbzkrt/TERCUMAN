package com.genco.tercuman

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    fun writePcm16Mono(file: File, pcm: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val dataSize = pcm.size * 2
            val byteRate = sampleRate * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            out.write(header.array())
            val audio = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { audio.putShort(it) }
            out.write(audio.array())
        }
    }
}
