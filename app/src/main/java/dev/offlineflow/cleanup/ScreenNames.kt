package dev.offlineflow.cleanup

/**
 * Picks the names and jargon out of the text on screen (a chat's contact, an email thread's
 * people, a product name) so a name you say gets spelled the way the screen spells it.
 * Used for one dictation and dropped; never stored.
 */
object ScreenNames {
    private val WORD = Regex("[\\p{L}][\\p{L}\\p{N}'\u2019-]*[\\p{L}\\p{N}]|[\\p{L}]")

    fun from(texts: List<String>, isCommon: (String) -> Boolean, limit: Int = 30): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (text in texts) {
            if (text.length > 4000) continue
            for (m in WORD.findAll(text)) {
                val w = m.value.removeSuffix("'s").removeSuffix("\u2019s")
                if (w.length < 3 || w.length > 30) continue
                if (!w.first().isUpperCase() && !w.drop(1).any { it.isUpperCase() }) continue // name-like: Capital or iPhone
                if (w.length > 4 && w.all { !it.isLetter() || it.isUpperCase() }) continue // SHOUTED HEADINGS
                // Skip handles, addresses and links: names glued to @ . / : are not spoken that way.
                val prev = text.getOrNull(m.range.first - 1); val next = text.getOrNull(m.range.last + 1)
                if (prev == '@' || prev == '#' || prev == '/' || next == '@' || (next == '.' && text.getOrNull(m.range.last + 2)?.isLetter() == true)) continue
                if (isCommon(w)) continue
                counts[w] = (counts[w] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }
}
