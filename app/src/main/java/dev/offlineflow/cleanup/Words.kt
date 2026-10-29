package dev.offlineflow.cleanup

import android.content.Context

/**
 * Everyday English words (SCOWL sizes 10–35, lowercase entries only; see assets/words/SCOWL-LICENSE.txt).
 * Used to tell names and jargon ("Mugdha", "GitHub") apart from ordinary words ("there", "send").
 */
object Words {
    @Volatile private var set: Set<String>? = null

    fun common(context: Context): Set<String> = set ?: synchronized(this) {
        set ?: runCatching {
            context.assets.open("words/common-en.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        }.getOrDefault(emptySet<String>()).also { set = it }
    }

    /** True for a word an ordinary English speaker types every day, in any case. */
    fun isCommon(context: Context, word: String) = word.lowercase().replace('\u2019', '\'') in common(context)
}
