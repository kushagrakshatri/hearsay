package dev.offlineflow.asr

/**
 * Turns the user's dictionary into hotwords for Parakeet: words and names the speech model is nudged
 * to write exactly like this when it hears something close. Without it Parakeet has never heard
 * "Kushagra" or "Hearsay" and writes "Cashagra" / "hear say", and the cleanup model can only guess back.
 *
 * The boost is per piece of the word, and it has a sweet spot (tools/eval/asr): on the names set (60-word dictionary)
 * 1.5 was best and 1.75 started writing dictionary words nobody said; on real speech with ~100 terms in the list
 * 1.5 already hurt overall WER while 1.25 did not. 1.25 keeps a margin.
 */
object Hotwords {
    const val SCORE = 1.25f

    /** More than this slows the search and makes stray matches likelier. */
    const val MAX_TERMS = 100

    /** Terms worth boosting: 3+ characters, letters/digits/spaces/apostrophes only (the speech model's alphabet), each once. */
    fun terms(spellings: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in spellings) {
            val t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.length < 3 || !t.any { it.isLetter() }) continue
            if (!t.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == ' ' || it == '\'' }) continue
            if (seen.add(t.lowercase())) out += t
            if (out.size >= MAX_TERMS) break
        }
        return out
    }

    /** The per-dictation hotword string sherpa-onnx takes (terms separated by "/"), or null when there is nothing to boost. */
    fun forStream(spellings: List<String>): String? = terms(spellings).takeIf { it.isNotEmpty() }?.joinToString("/")

    /**
     * sherpa-onnx cuts a hotword into pieces with a "piece score" vocabulary. Parakeet's SentencePiece scores are
     * minus each piece's row in tokens.txt, so the vocabulary can be made from tokens.txt (the blank is left out).
     */
    fun vocabulary(tokensTxt: String): String = buildString {
        tokensTxt.lineSequence().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            val piece = line.substringBeforeLast(' ')
            if (piece != "<blk>") append(piece).append(' ').append(-i).append('\n')
        }
    }
}
