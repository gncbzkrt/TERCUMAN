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
import java.security.MessageDigest

class ModelManager(private val context: Context) {
    companion object {
        const val WHISPER_NAME = "ggml-base.bin"
        const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"

        const val STREAM_DIR = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11"
        const val STREAM_ARCHIVE = "$STREAM_DIR.tar.bz2"
        const val STREAM_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$STREAM_ARCHIVE"

        const val SUPER_DIR = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        const val SUPER_ARCHIVE = "$SUPER_DIR.tar.bz2"
        const val SUPER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$SUPER_ARCHIVE"
        const val AI_DIR = "ai"
        const val AI_MODEL_NAME = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        const val AI_MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/$AI_MODEL_NAME?download=true"
        const val AI_MODEL_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"

        const val DIARIZATION_DIR = "sherpa-onnx-pyannote-segmentation-3-0"
        const val DIARIZATION_ARCHIVE = "$DIARIZATION_DIR.tar.bz2"
        const val DIARIZATION_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/$DIARIZATION_ARCHIVE"
        const val SPEAKER_EMBEDDING_NAME = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        const val SPEAKER_EMBEDDING_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$SPEAKER_EMBEDDING_NAME"
    }

    val modelRoot: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val whisperFile: File get() = File(modelRoot, WHISPER_NAME)
    val streamingDir: File get() = File(modelRoot, STREAM_DIR)
    val supertonicDir: File get() = File(modelRoot, SUPER_DIR)
    val aiDir: File get() = File(modelRoot, AI_DIR)
    val aiModelFile: File get() = File(aiDir, AI_MODEL_NAME)
    val diarizationDir: File get() = File(modelRoot, DIARIZATION_DIR)
    val speakerEmbeddingFile: File get() = File(modelRoot, SPEAKER_EMBEDDING_NAME)

    fun whisperReady(): Boolean = whisperFile.exists() && whisperFile.length() > 100_000_000
    fun streamingReady(): Boolean = listOf(
        "encoder.int8.onnx", "decoder.int8.onnx",
        "joiner.int8.onnx", "tokens.txt"
    ).all { File(streamingDir, it).exists() && File(streamingDir, it).length() > 0 }
    fun aiReady(): Boolean =
        aiModelFile.exists() && aiModelFile.length() > 300_000_000

    fun diarizationReady(): Boolean =
        File(diarizationDir, "model.onnx").exists() &&
        File(diarizationDir, "model.onnx").length() > 0 &&
        speakerEmbeddingFile.exists() &&
        speakerEmbeddingFile.length() > 1000000

    fun supertonicReady(): Boolean = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"
    ).all { File(supertonicDir, it).exists() }

    suspend fun ensureAiCore(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (aiReady()) return@withContext
        aiDir.mkdirs()
        download(AI_MODEL_URL, aiModelFile, onProgress)
        check(aiReady()) { "AI Conversation modeli açılamadı." }
        check(sha256(aiModelFile).equals(AI_MODEL_SHA256, ignoreCase = true)) {
            aiModelFile.delete()
            "AI Conversation modeli doğrulanamadı."
        }
    }

    suspend fun ensureStreamingMultilingual(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (streamingReady()) return@withContext
        val archive = File(modelRoot, STREAM_ARCHIVE)
        download(STREAM_URL, archive, onProgress)
        extractTarBz2(archive, modelRoot)
        archive.delete()
        check(streamingReady()) { "Streaming çoklu dil modeli açılamadı." }
    }

    suspend fun ensureWhisper(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (whisperReady()) return@withContext
        download(WHISPER_URL, whisperFile, onProgress)
    }

    suspend fun ensureDiarization(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (diarizationReady()) return@withContext
        val archive = File(modelRoot, DIARIZATION_ARCHIVE)
        download(DIARIZATION_URL, archive, onProgress)
        extractTarBz2(archive, modelRoot)
        archive.delete()
        download(SPEAKER_EMBEDDING_URL, speakerEmbeddingFile, onProgress)
        check(diarizationReady()) { "Konuşmacı modelleri açılamadı." }
    }

    suspend fun ensureSupertonic(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (supertonicReady()) return@withContext
        val archive = File(modelRoot, SUPER_ARCHIVE)
        download(SUPER_URL, archive, onProgress)
        extractTarBz2(archive, modelRoot)
        archive.delete()
        check(supertonicReady()) { "Supertonic modeli açılamadı." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 128)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
