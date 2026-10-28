package dev.offlineflow

import android.content.Context

/** Small user settings, all on-device in the "hearsay" preferences. */
object Prefs {
    private const val NAME = "hearsay"
    private const val PAUSE = "pause_ms"
    private const val HAPTICS = "haptics"
    const val STYLE_PREFIX = "style:"

    /** Hands-free auto-stop choices: (milliseconds of silence after speech, label). */
    val PAUSES = listOf(1000 to "Short · 1 s", 1500 to "Normal · 1.5 s", 2500 to "Long · 2.5 s")

    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun pauseMs(c: Context): Int = p(c).getInt(PAUSE, 1500).takeIf { ms -> PAUSES.any { it.first == ms } } ?: 1500
    fun setPauseMs(c: Context, ms: Int) = p(c).edit().putInt(PAUSE, ms).apply()

    fun haptics(c: Context): Boolean = p(c).getBoolean(HAPTICS, true)
    fun setHaptics(c: Context, on: Boolean) = p(c).edit().putBoolean(HAPTICS, on).apply()

    // Opt-in cloud mode (user's own key). Stored in private app storage, never logged or synced.
    fun cloudUrl(c: Context): String = p(c).getString("cloud_url", null) ?: "https://api.openai.com/v1"
    fun cloudKey(c: Context): String = p(c).getString("cloud_key", null) ?: ""
    fun cloudSpeechModel(c: Context): String = p(c).getString("cloud_asr_model", null) ?: "whisper-1"
    fun cloudCleanupModel(c: Context): String = p(c).getString("cloud_llm_model", null) ?: "gpt-4o-mini"
    fun cloudSpeech(c: Context): Boolean = p(c).getBoolean("cloud_speech", false)
    fun cloudCleanup(c: Context): Boolean = p(c).getBoolean("cloud_cleanup", false)

    fun saveCloud(c: Context, url: String, key: String, asrModel: String, llmModel: String, speech: Boolean, cleanup: Boolean) =
        p(c).edit()
            .putString("cloud_url", url.trim().ifEmpty { "https://api.openai.com/v1" })
            .putString("cloud_key", key.trim())
            .putString("cloud_asr_model", asrModel.trim().ifEmpty { "whisper-1" })
            .putString("cloud_llm_model", llmModel.trim().ifEmpty { "gpt-4o-mini" })
            .putBoolean("cloud_speech", speech)
            .putBoolean("cloud_cleanup", cleanup)
            .apply()

    // Sign in with ChatGPT: cleanup and commands on the user's ChatGPT plan (tokens live in ChatGptPlan's file).
    fun chatGptCleanup(c: Context): Boolean = p(c).getBoolean("chatgpt_cleanup", true)
    fun setChatGptCleanup(c: Context, on: Boolean) = p(c).edit().putBoolean("chatgpt_cleanup", on).apply()
    fun chatGptModel(c: Context): String = p(c).getString("chatgpt_model", null) ?: "gpt-6-luna"
    fun setChatGptModel(c: Context, m: String) = p(c).edit().putString("chatgpt_model", m.trim().ifEmpty { "gpt-6-luna" }).apply()
    fun chatGptWelcomed(c: Context): Boolean = p(c).getBoolean("chatgpt_welcomed", false)
    fun setChatGptWelcomed(c: Context) = p(c).edit().putBoolean("chatgpt_welcomed", true).apply()

    /** Apps where the user picked a style with the keyboard chip. */
    fun styleOverrides(c: Context): Int = p(c).all.keys.count { it.startsWith(STYLE_PREFIX) }

    fun resetStyleOverrides(c: Context) {
        val e = p(c).edit()
        p(c).all.keys.filter { it.startsWith(STYLE_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }
}
