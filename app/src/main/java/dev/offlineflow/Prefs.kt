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

    /** Apps where the user picked a style with the keyboard chip. */
    fun styleOverrides(c: Context): Int = p(c).all.keys.count { it.startsWith(STYLE_PREFIX) }

    fun resetStyleOverrides(c: Context) {
        val e = p(c).edit()
        p(c).all.keys.filter { it.startsWith(STYLE_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }
}
