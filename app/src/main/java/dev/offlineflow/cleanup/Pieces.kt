package dev.offlineflow.cleanup

/**
 * Live cleanup works on the pieces the recorder hands over at pauses (core/LiveSpeech). A pause
 * usually ends a sentence, so pieces are cleaned on their own while the user keeps talking and
 * only the last one is left when the mic is released. A piece that corrects or continues the one
 * before ("no wait, Friday", "second, …", "and then …") is cleaned together with it, so fixes and
 * lists that span a pause still see both halves.
 */
object Pieces {
    private val CONTINUES = Regex(
        "(?i)^\\W*(?:no\\b|wait\\b|actually\\b|sorry\\b|scratch that\\b|i mean\\b|or rather\\b|rather\\b|make (?:it|that)\\b|" +
            "instead\\b|second(?:ly)?\\b|third(?:ly)?\\b|fourth(?:ly)?\\b|fifth(?:ly)?\\b|next\\b|then\\b|finally\\b|lastly\\b|" +
            "and\\b|but\\b|or\\b|so\\b|because\\b|which\\b|that\\b|plus\\b|also\\b)",
    )

    /** True when [piece] corrects or carries on from the piece before it. */
    fun continues(piece: String): Boolean = CONTINUES.containsMatchIn(piece)

    /** Pieces grouped into stretches that can be cleaned independently. */
    fun units(pieces: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in pieces.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.isNotEmpty() && continues(p)) out[out.size - 1] = Joins.join(listOf(out.last(), p)) else out += p
        }
        return out
    }

    /**
     * Cleaned stretches back into one text. Where the next stretch carries on the sentence
     * ([Joins]), the period at the pause goes; otherwise a stretch the model left unpunctuated gets one.
     */
    fun join(cleaned: List<String>): String = Joins.join(cleaned, period = true)
}
