package dev.offlineflow.cleanup

/**
 * Learns from the user's fixes. Compares what Hearsay inserted with what the user left there
 * after editing, and keeps the small word swaps that look like a misheard name or term
 * ("Kushagr" → "Kushagra", "cash agra" → "Kushagra", "github" → "GitHub").
 *
 * It never learns a swap to an everyday word ("their" → "there"): that's grammar, not a name.
 * Bigger rewrites are ignored, since those are the user changing what they meant to say.
 */
object Learner {
    data class Learned(val heard: String, val written: String) {
        /** Only the spelling's case changed, so it's a term ("GitHub"), not a fix. */
        val caseOnly: Boolean get() = norm(heard) == norm(written)

        /** The dictionary line this becomes. A swap away from an everyday word is kept as a plain term. */
        fun line(isCommon: (String) -> Boolean): String =
            if (caseOnly || (!heard.contains(' ') && isCommon(heard))) written else "$heard -> $written"
    }

    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'\u2019-]*")

    fun corrections(inserted: String, edited: String, isCommon: (String) -> Boolean): List<Learned> {
        val a = WORD.findAll(inserted).map { it.value }.toList()
        val b = WORD.findAll(edited).map { it.value }.toList()
        if (a.isEmpty() || b.isEmpty() || a == b) return emptyList()
        val hunks = diff(a, b).filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        if (hunks.isEmpty() || hunks.size > 3) return emptyList()
        // Rewrote a good part of it: that's a change of mind, not a fix.
        val changed = hunks.sumOf { it.first.size }
        if (a.size >= 5 && changed > a.size * 0.4) return emptyList()
        return hunks.mapNotNull { (old, new) -> judge(old, new, isCommon) }.distinct()
    }

    private fun judge(old: List<String>, new: List<String>, isCommon: (String) -> Boolean): Learned? {
        if (old.size > 3 || new.size > 2) return null
        val heard = old.joinToString(" ")
        val written = new.joinToString(" ")
        val h = norm(heard); val w = norm(written)
        if (w.length < 2 || new.any { it.length < 2 && !it.first().isDigit() }) return null
        if (h == w) {
            // Case only. Worth keeping when it's a real spelling (GitHub, iPhone, F5), not sentence case.
            if (written == written.lowercase()) return null
            val sentenceCase = written == heard.lowercase().replaceFirstChar { it.uppercase() }
            if (sentenceCase && new.all { isCommon(it) }) return null
            if (new.all { isCommon(it) } && !written.drop(1).any { it.isUpperCase() }) return null
            return Learned(heard, written)
        }
        // Spelled differently: must be a name or jargon, and sound like what was heard.
        if (new.all { isCommon(it) }) return null
        if (w.startsWith(h) && w.length - h.length > 3) return null // still typing / added a word
        val limit = maxOf(2, (w.length * 0.4).toInt())
        if (editDistance(h, w) > limit) return null
        if (h.first() != w.first() && !soundsAlike(h.first(), w.first())) return null
        return Learned(heard, written)
    }

    /**
     * Adds what was learned to the dictionary text. Returns the new text and what was added,
     * or null when it already knew all of it.
     */
    fun addTo(raw: String, learned: List<Learned>, isCommon: (String) -> Boolean, max: Int = 400): Pair<String, List<Learned>>? {
        val dict = Dictionary.parse(raw)
        val terms = dict.terms.toHashSet()
        val knownFixes = dict.fixes.map { it.first.lowercase() }.toHashSet()
        val count = dict.terms.size + dict.fixes.size
        val added = ArrayList<Learned>()
        val lines = ArrayList<String>()
        for (l in learned) {
            if (count + added.size >= max) break
            val line = l.line(isCommon)
            val isFix = line.contains("->")
            if (if (isFix) l.heard.lowercase() in knownFixes else l.written in terms) continue
            lines += line; added += l
            if (isFix) knownFixes += l.heard.lowercase() else terms += l.written
        }
        if (added.isEmpty()) return null
        // A case-only term replaces an older spelling of the same word.
        val kept = raw.lines().filterNot { line ->
            val t = line.trim()
            !t.contains("->") && added.any { it.caseOnly && t.equals(it.written, true) && t != it.written }
        }
        val text = (kept.joinToString("\n").trimEnd() + "\n" + lines.joinToString("\n")).trim()
        return text to added
    }

    /** Word-level diff: the (removed, added) runs between [a] and [b], by longest common subsequence. */
    internal fun diff(a: List<String>, b: List<String>): List<Pair<List<String>, List<String>>> {
        val n = a.size; val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val out = ArrayList<Pair<List<String>, List<String>>>()
        var i = 0; var j = 0
        val del = ArrayList<String>(); val ins = ArrayList<String>()
        fun flush() { if (del.isNotEmpty() || ins.isNotEmpty()) out += del.toList() to ins.toList(); del.clear(); ins.clear() }
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> { flush(); i++; j++ }
                j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> ins += b[j++]
                else -> del += a[i++]
            }
        }
        flush()
        return out
    }

    private fun soundsAlike(x: Char, y: Char): Boolean {
        val groups = listOf("ckq", "sz", "fv", "bp", "dt", "gj", "aeiouy", "mn", "wv")
        return groups.any { x in it && y in it }
    }

    internal fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
