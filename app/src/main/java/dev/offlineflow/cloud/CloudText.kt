package dev.offlineflow.cloud

import android.content.Context
import dev.offlineflow.BuildConfig
import dev.offlineflow.Prefs

/**
 * Where cloud cleanup and commands go: the user's ChatGPT plan when signed in (preferred),
 * else their own API key. Null/false means everything stays on the phone.
 */
object CloudText {
    fun on(c: Context): Boolean = BuildConfig.CLOUD && (ChatGptPlan.active(c) || CloudClient.cleanupOn(c))

    fun usingPlan(c: Context): Boolean = ChatGptPlan.active(c)

    /** Blocking; run off the main thread. */
    fun chat(c: Context, messages: List<Pair<String, String>>, maxTokens: Int): String =
        if (ChatGptPlan.active(c)) ChatGptPlan.respond(c, messages)
        else CloudClient.from(c)?.chat(Prefs.cloudCleanupModel(c), messages, maxTokens) ?: error("cloud cleanup not configured")
}
