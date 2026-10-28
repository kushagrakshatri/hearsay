package dev.offlineflow.cleanup

import android.content.Context

/**
 * The user's personal dictionary, one entry per line:
 *   Hearsay                 a word or name to spell exactly this way
 *   hear say -> Hearsay     a fix: whenever the left side is heard, write the right side
 * Stored on-device in app preferences; never leaves the phone.
 */
class Dictionary(val terms: List<String>, val fixes: List<Pair<String, String>>) {

    val isEmpty: Boolean get() = terms.isEmpty() && fixes.isEmpty()

    /** Everything the LLM should spell exactly: plain terms plus each fix's written form. */
    val spellings: List<String> get() = (terms + fixes.map { it.second }).distinct()

    /** Stable key so a cached LLM prompt can tell when the dictionary changed. */
    val key: String get() = spellings.joinToString("\u0001")

    /**
     * Applies fixes, then corrects each term's spelling where the transcript has it with
     * the wrong case ("github" → "GitHub"), split into pieces ("hear say" → "Hearsay"),
     * or one letter off for longer words ("Kushagr" → "Kushagra").
     */
    fun apply(text: String): String {
        if (isEmpty || text.isBlank()) return text
        var t = text
        for ((heard, written) in fixes) t = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(heard)}(?![\\p{L}\\p{N}])").replace(t) { written }
        for (term in terms) t = correctTerm(t, term)
        return t
    }

    private fun correctTerm(text: String, term: String): String {
        val target = norm(term)
        if (target.length < 2) return text
        val words = WORD.findAll(text).toList()
        if (words.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        val termWords = term.trim().split(Regex("\\s+")).size
        while (i < words.size) {
            var matched = 0
            // Try spans of up to (term words + 2) transcript words, longest first.
            for (span in minOf(termWords + 2, words.size - i) downTo 1) {
                val piece = words.subList(i, i + span)
                // Only join words separated by plain spaces or hyphens, never across punctuation.
                val gapsOk = (0 until span - 1).all { k ->
                    text.substring(piece[k].range.last + 1, piece[k + 1].range.first).all { it == ' ' || it == '-' }
                }
                if (!gapsOk) continue
                val heard = norm(piece.joinToString("") { it.value })
                if (heard == target || (span == 1 && termWords == 1 && target.length >= 6 && heard.length >= 5 &&
                        heard.first() == target.first() && editDistance(heard, target) <= 1)) {
                    matched = span; break
                }
            }
            if (matched > 0) {
                val first = words[i].range.first
                val last = words[i + matched - 1].range.last
                out.append(text, cursor, first).append(term.trim())
                cursor = last + 1
                i += matched
            } else i++
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    companion object {
        private const val PREFS = "hearsay"
        private const val KEY = "dictionary"
        private val WORD = Regex("[\\p{L}\\p{N}']+")
        private val ARROW = Regex("\\s*(?:->|→|=>)\\s*")

        fun parse(raw: String): Dictionary {
            val terms = ArrayList<String>()
            val fixes = ArrayList<Pair<String, String>>()
            for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val parts = line.split(ARROW, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) fixes += parts[0].trim() to parts[1].trim()
                else terms += line
            }
            return Dictionary(terms.distinct(), fixes)
        }

        fun load(context: Context): Dictionary = parse(text(context))

        fun text(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

        fun save(context: Context, raw: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw.trim()).apply()

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        private fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}
