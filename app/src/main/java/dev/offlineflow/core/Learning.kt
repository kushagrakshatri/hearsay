package dev.offlineflow.core

import android.content.Context
import dev.offlineflow.Prefs
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.Learner
import dev.offlineflow.cleanup.Words

/** Turns a fix the user made to dictated text into dictionary entries. */
object Learning {
    /** Learns from [inserted] → [edited]; returns the words it added (empty when nothing new). */
    fun learn(ctx: Context, inserted: String, edited: String): List<String> {
        if (!Prefs.learnWords(ctx)) return emptyList()
        val common = Words.common(ctx)
        val isCommon = { w: String -> w.lowercase().replace('\u2019', '\'') in common }
        val found = Learner.corrections(inserted, edited, isCommon)
        if (found.isEmpty()) return emptyList()
        val (text, added) = Learner.addTo(Dictionary.text(ctx), found, isCommon) ?: return emptyList()
        Dictionary.save(ctx, text)
        return added.map { it.written }
    }

    /** "Learned “Kushagra”" / "Learned “Kushagra” and “GitHub”". */
    fun message(words: List<String>) =
        "Learned " + words.take(2).joinToString(" and ") { "\u201c$it\u201d" }
}
