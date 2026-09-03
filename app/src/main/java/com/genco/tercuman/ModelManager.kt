package com.genco.tercuman

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {
    companion object {
        // Canlı çeviri için tiny-q8_0: küçük ve düşük gecikmeli multilingual Whisper modeli.
        // Çok dilli Whisper modelidir; Türkçe dahil desteklenir.
        const val WHISPER_NAME = "ggml-tiny-q8_0.bin"
        const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin?download=true"
        private const val LEGACY_BASE_NAME = "ggml-base.bin"

        const val SUPER_DIR = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        const val SUPER_ARCHIVE = "$SUPER_DIR.tar.bz2"
        const val SUPER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$SUPER_ARCHIVE"
    }

    val modelRoot: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val whisperFile: File get() = File(modelRoot, WHISPER_NAME)
    val supertonicDir: File get() = File(modelRoot, SUPER_DIR)

    fun whisperReady(): Boolean = whisperFile.exists() && whisperFile.length() > 35_000_000
    fun supertonicReady(): Boolean = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"
    ).all { File(supertonicDir, it).exists() }

    suspend fun ensureWhisper(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (whisperReady()) return@withContext
        download(WHISPER_URL, whisperFile, onProgress)
        // v0.1/v0.2 base modeli artık gereksiz; depolama alanını geri ver.
        runCatching { File(modelRoot, LEGACY_BASE_NAME).delete() }
    }

    suspend fun ensureSupertonic(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (supertonicReady()) return@withContext
        val archive = File(modelRoot, SUPER_ARCHIVE)
        download(SUPER_URL, archive, onProgress)
        extractTarBz2(archive, modelRoot)
        archive.delete()
        check(supertonicReady()) { "Supertonic modeli açılamadı." }
    }

    private fun download(urlText: String, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        var conn = URL(urlText).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "TERCUMAN-Android")
        conn.connect()
        if (conn.responseCode in 300..399) {
            val next = conn.getHeaderField("Location") ?: error("İndirme yönlendirmesi alınamadı")
            conn.disconnect()
            conn = URL(next).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TERCUMAN-Android")
            conn.connect()
        }
        check(conn.responseCode in 200..299) { "Model indirilemedi: HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong
        var done = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(1024 * 128)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "Model dosyası kaydedilemedi" }
        onProgress(100)
    }

    private fun extractTarBz2(archive: File, destination: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(destination, entry.name)
                val canonicalRoot = destination.canonicalFile
                val canonicalOut = out.canonicalFile
                check(canonicalOut.path.startsWith(canonicalRoot.path + File.separator)) { "Geçersiz arşiv yolu" }
                if (entry.isDirectory) {
                    canonicalOut.mkdirs()
                } else {
                    canonicalOut.parentFile?.mkdirs()
                    FileOutputStream(canonicalOut).use { output -> tar.copyTo(output) }
                }
            }
        }
    }
}
