package dev.offlineflow.model

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and stores on-device models. The only network use in the app. */
class ModelManager(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    enum class Model(val fileName: String, val url: String, val approxMb: Int) {
        BASE_EN_Q5("ggml-base.en-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin", 60),
        SMALL_EN_Q5("ggml-small.en-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin", 190),
    }

    fun file(m: Model) = File(dir, m.fileName)
    fun isReady(m: Model) = file(m).let { it.exists() && it.length() > 1_000_000 }

    /** Blocking; call off the main thread. Resumes partial downloads. */
    fun download(m: Model, onProgress: (Int) -> Unit) {
        val part = File(dir, m.fileName + ".part")
        var done = if (part.exists()) part.length() else 0L
        val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
            connectTimeout = 15_000; readTimeout = 30_000
        }
        if (done > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) { part.delete(); done = 0 }
        val total = done + conn.contentLengthLong
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, done > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        check(part.renameTo(file(m))) { "Could not save model" }
        // TODO(M1): verify SHA-256 against a pinned checksum.
    }
}
