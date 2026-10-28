package dev.offlineflow.cleanup

/**
 * Continues a sentence the user already started: when the text before the cursor doesn't end
 * a sentence, a capitalized common word at the start of the dictation is lowercased
 * ("I'll be there" + "And bring snacks." → "and bring snacks."). Names are never touched,
 * because only words in [COMMON] are lowercased. Reads two characters before the cursor; nothing is stored.
 */
object Continuity {
    private val COMMON = setOf(
        "and", "but", "or", "so", "because", "then", "the", "a", "an", "to", "at", "in", "on", "for", "with",
        "which", "that", "who", "if", "when", "while", "it", "it's", "we", "you", "they", "he", "she", "my", "our",
        "your", "their", "this", "these", "those", "is", "was", "are", "will", "not", "also", "too", "as", "of",
        "from", "by", "about", "just", "maybe", "since", "until", "after", "before", "though", "although",
        "plus", "even", "only", "still", "there", "here", "what", "how", "where", "why", "do", "does", "did",
        "can", "could", "would", "should", "be", "been", "have", "has", "had", "all", "some", "any", "more",
        "less", "very", "really", "like", "yet", "nor", "into", "over", "under", "around", "through", "without",
    )

    fun apply(text: String, before: CharSequence?): String {
        if (text.length < 2 || before.isNullOrEmpty()) return text
        val prev = before.toString().trimEnd(' ', '\t')
        if (prev.isEmpty() || prev.last() in ".!?\n") return text
        if (!text[0].isUpperCase() || !text[1].isLowerCase()) return text
        val first = text.takeWhile { it.isLetter() || it == '\'' }.lowercase()
        return if (first in COMMON) text.replaceFirstChar { it.lowercaseChar() } else text
    }
}
