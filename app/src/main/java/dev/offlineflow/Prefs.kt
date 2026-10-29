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

    /** The guided first-run setup was finished or skipped. */
    // Floating mic: shown when its accessibility service is on and this is true; position is
    // x from the right edge and y relative to the keyboard's top (MIN_VALUE = default).
    fun bubbleOn(c: Context): Boolean = p(c).getBoolean("bubble_on", true)
    fun setBubbleOn(c: Context, on: Boolean) = p(c).edit().putBoolean("bubble_on", on).apply()
    fun bubbleX(c: Context): Int = p(c).getInt("bubble_x", -1)
    fun bubbleDy(c: Context): Int = p(c).getInt("bubble_dy", Int.MIN_VALUE)
    fun setBubblePos(c: Context, x: Int, dy: Int) = p(c).edit().putInt("bubble_x", x).putInt("bubble_dy", dy).apply()
    /** "package|reason" from the floating mic's last check (empty reason = it showed). */
    fun bubbleWhy(c: Context): String = p(c).getString("bubble_why", null) ?: ""
    fun setBubbleWhy(c: Context, v: String) = p(c).edit().putString("bubble_why", v).apply()
    fun resetBubblePos(c: Context) = p(c).edit().remove("bubble_x").remove("bubble_dy").apply()

    /** Learn names and terms from the user's fixes to dictated text. */
    fun learnWords(c: Context): Boolean = p(c).getBoolean("learn_words", true)
    fun setLearnWords(c: Context, on: Boolean) = p(c).edit().putBoolean("learn_words", on).apply()

    /** Floating mic: read names visible on screen (for that one dictation, never stored) to spell them right. */
    fun screenContext(c: Context): Boolean = p(c).getBoolean("screen_context", true)
    fun setScreenContext(c: Context, on: Boolean) = p(c).edit().putBoolean("screen_context", on).apply()

    fun setupDone(c: Context): Boolean = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context) = p(c).edit().putBoolean("setup_done", true).apply()

    /** Apps where the user picked a style with the keyboard chip. */
    fun styleOverrides(c: Context): Int = p(c).all.keys.count { it.startsWith(STYLE_PREFIX) }

    fun resetStyleOverrides(c: Context) {
        val e = p(c).edit()
        p(c).all.keys.filter { it.startsWith(STYLE_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }
}
