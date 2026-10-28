package dev.offlineflow.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal streaming client for POST /v1/responses as the ChatGPT plan flow requires it:
 * store=false, stream=true, no temperature or max_output_tokens, system text in `instructions`.
 */
object Responses {
    private const val URL_RESPONSES = "https://api.openai.com/v1/responses"

    class HttpError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

    fun stream(accessToken: String, model: String, messages: List<Pair<String, String>>, effort: String): String {
        val instructions = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val input = JSONArray().apply {
            messages.filter { it.first != "system" }.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        }
        val req = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("reasoning", JSONObject().put("effort", effort))
            .put("store", false)
            .put("stream", true)
        val conn = (URL(URL_RESPONSES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code, conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            val out = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (ev.optString("type")) {
                        "response.output_text.delta" -> out.append(ev.optString("delta"))
                        "response.completed" -> return out.toString()
                        "response.failed", "response.incomplete", "error" -> {
                            val err = ev.optJSONObject("response")?.optJSONObject("error") ?: ev.optJSONObject("error") ?: ev
                            throw HttpError(200, err.optString("code").ifEmpty { ev.optString("type") } + " " + err.optString("message"))
                        }
                    }
                }
            }
            throw HttpError(200, "stream ended before response.completed")
        } finally {
            conn.disconnect()
        }
    }
}
