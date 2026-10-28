package dev.offlineflow.cloud

import android.content.Context
import dev.offlineflow.BuildConfig
import dev.offlineflow.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opt-in cloud mode: the user's own key for an OpenAI-compatible API (OpenAI, Groq, a self-hosted
 * server...). Off by default; compiled out of builds made with -PnoCloud (F-Droid).
 * Blocking calls; run off the main thread.
 */
class CloudClient(private val baseUrl: String, private val apiKey: String) {

    /** POST /audio/transcriptions with the recording as 16-bit WAV. */
    fun transcribe(samples: FloatArray, model: String): String {
        val boundary = "hearsay" + System.nanoTime()
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray()); out.writeBytes("\r\n")
            }
            field("model", model)
            field("language", "en")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wav(samples))
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val json = post("audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray())
        return JSONObject(json).optString("text").trim()
    }

    /** POST /chat/completions, temperature 0. [messages] are (role, content) pairs. */
    fun chat(model: String, messages: List<Pair<String, String>>, maxTokens: Int): String {
        val req = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().apply {
                messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
            })
        val json = post("chat/completions", "application/json", req.toString().toByteArray())
        return JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun post(path: String, contentType: String, body: ByteArray): String {
        val conn = (URL(baseUrl.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", contentType)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("Cloud service returned $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The client when cloud mode is built in and configured, else null. */
        fun from(context: Context): CloudClient? {
            if (!BuildConfig.CLOUD) return null
            val key = Prefs.cloudKey(context)
            val url = Prefs.cloudUrl(context)
            if (key.isBlank() || !url.startsWith("https://")) return null
            return CloudClient(url, key)
        }

        fun speechOn(context: Context) = Prefs.cloudSpeech(context) && from(context) != null
        fun cleanupOn(context: Context) = Prefs.cloudCleanup(context) && from(context) != null

        /** 16 kHz mono float PCM → 16-bit PCM WAV bytes. */
        fun wav(samples: FloatArray, rate: Int = 16_000): ByteArray {
            val data = samples.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            return b.array()
        }
    }
}
