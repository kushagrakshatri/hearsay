package dev.offlineflow.cleanup

/**
 * Command mode: decides whether an utterance is an edit instruction rather than dictation.
 *  - With text selected, anything that starts with an editing verb ("make it shorter",
 *    "turn this into bullets", "translate to Spanish") edits the selection.
 *  - With nothing selected, the verb must point at "this" or "that" ("make that more formal")
 *    and edits the text Hearsay just inserted.
 *  - "scratch that" removes the last insert.
 */
object Commands {
    private const val LEAD = "(?:(?:ok|okay|hey|please|can you|could you|now)\\s+)*"
    private const val VERBS = "make|turn|rewrite|rephrase|reword|shorten|summarize|summarise|translate|fix|change|" +
        "convert|format|expand|simplify|polish|clean|tidy|proofread|capitalize|uppercase|lowercase|put|add|remove|" +
        "correct|improve|condense|elaborate|edit|redo|reformat"

    private val SELECTION = Regex("^$LEAD(?:$VERBS)\\b")
    private val LAST = Regex("^$LEAD(?:$VERBS)\\s+(?:up\\s+)?(?:this|that)\\b")
    private val SCRATCH = setOf("scratch that", "delete that", "undo that", "erase that")

    /** Edit instruction for the selected text? */
    fun forSelection(utterance: String): Boolean = SELECTION.containsMatchIn(norm(utterance))

    /** Edit instruction aimed at the text just inserted? */
    fun forLastInsert(utterance: String): Boolean = LAST.containsMatchIn(norm(utterance))

    /** "Scratch that": remove the last insert. */
    fun isScratch(utterance: String): Boolean = norm(utterance) in SCRATCH

    /** The instruction as the model should see it: no trailing ASR period, first letter capitalized. */
    fun instruction(utterance: String): String =
        utterance.trim().trimEnd('.', '!').trim().replaceFirstChar { it.uppercaseChar() }

    internal fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()
}
