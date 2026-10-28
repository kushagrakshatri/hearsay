package dev.offlineflow.model

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies and stores on-device speech models. The only network use in the app.
 * Every file is pinned by size and SHA-256; a mismatch is deleted, never loaded.
 */
class ModelManager(context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("hearsay", Context.MODE_PRIVATE)
    private val totalRam: Long = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    enum class Engine { WHISPER, PARAKEET }

    data class Asset(val name: String, val url: String, val bytes: Long, val sha256: String)

    enum class Model(val label: String, val engine: Engine, val subdir: String, val assets: List<Asset>) {
        PARAKEET_V2(
            "Parakeet TDT 0.6B v2 (NVIDIA) · most accurate", Engine.PARAKEET, "parakeet-tdt-0.6b-v2-int8",
            listOf(
                hf("encoder.int8.onnx", 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                hf("decoder.int8.onnx", 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                hf("joiner.int8.onnx", 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                hf("tokens.txt", 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            ),
        ),
        WHISPER_BASE_EN(
            "Whisper base.en (OpenAI) · small and light", Engine.WHISPER, "",
            listOf(
                Asset(
                    "ggml-base.en-q5_1.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
                    59_721_011, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
                ),
            ),
        );

        val approxMb: Int get() = (assets.sumOf { it.bytes } / 1_000_000).toInt()
        val shortName: String get() = label.substringBefore(" (")
    }

    /** Parakeet peaks around 1.1 GB of RAM; give it to phones with ~6 GB or more. */
    val recommended: Model
        get() = if (totalRam >= 5_500_000_000L) Model.PARAKEET_V2 else Model.WHISPER_BASE_EN

    var selected: Model
        get() = prefs.getString(KEY_MODEL, null)
            ?.let { name -> Model.entries.firstOrNull { it.name == name } } ?: recommended
        set(value) { prefs.edit().putString(KEY_MODEL, value.name).apply() }

    fun dir(m: Model): File = if (m.subdir.isEmpty()) root else File(root, m.subdir)
    fun file(m: Model, asset: Asset = m.assets.first()) = File(dir(m), asset.name)
    fun isReady(m: Model) = m.assets.all { file(m, it).let { f -> f.exists() && f.length() == it.bytes } }

    /** Blocking; call off the main thread. Resumes partial downloads; verifies each file. */
    fun download(m: Model, onProgress: (Int) -> Unit) {
        dir(m).mkdirs()
        val total = m.assets.sumOf { it.bytes }
        var before = 0L
        for (a in m.assets) {
            val dest = file(m, a)
            if (!(dest.exists() && dest.length() == a.bytes)) {
                fetch(a, dest) { got -> onProgress(((before + got) * 100 / total).toInt()) }
            }
            before += a.bytes
            onProgress((before * 100 / total).toInt())
        }
    }

    private fun fetch(a: Asset, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        var done = if (part.exists()) part.length() else 0L
        if (done > a.bytes) { part.delete(); done = 0 }
        if (done < a.bytes) {
            val conn = (URL(a.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
                connectTimeout = 15_000; readTimeout = 30_000
            }
            if (done > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) { part.delete(); done = 0 }
            conn.inputStream.use { input ->
                FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - lastReport > 1_000_000) { onBytes(done); lastReport = done }
                    }
                }
            }
        }
        if (part.length() != a.bytes || sha256(part) != a.sha256) {
            part.delete()
            error("${a.name} failed verification, please download again")
        }
        check(part.renameTo(dest)) { "Could not save ${a.name}" }
    }

    companion object {
        private const val KEY_MODEL = "model"
        private const val PARAKEET_REPO =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

        private fun hf(name: String, bytes: Long, sha: String) = Asset(name, "$PARAKEET_REPO/$name", bytes, sha)

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
