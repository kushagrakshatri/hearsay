package dev.offlineflow.cleanup

/**
 * Picks, out of a list of names and words (seen on screen, or learned since the model's prompt
 * was built), only the ones that plausibly sound like something in the transcript. Those are
 * the only ones worth telling the LLM about: every extra word in the per-dictation note is
 * prompt the model has to read before it can start writing.
 */
object SoundAlike {
    private val WORD = Regex("[\\p{L}\\p{N}']+")

    /**
     * Up to [max] of [candidates] that sound like a word or short phrase in [transcript],
     * closest first. A name the transcript already spells exactly is left out: nothing to fix.
     */
    fun matches(candidates: List<String>, transcript: String, max: Int = 5): List<String> {
        if (candidates.isEmpty() || transcript.isBlank() || max <= 0) return emptyList()
        val words = WORD.findAll(transcript).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        val normWords = words.map(::norm)
        val keys = HashMap<String, String>()
        val scored = ArrayList<Pair<String, Int>>()
        for (name in candidates.distinct()) {
            val target = norm(name)
            if (target.length < 3) continue
            if (spelledExactly(transcript, name.trim())) continue
            val nameWords = name.trim().split(Regex("\\s+")).size
            val targetKey = key(target)
            var best = Int.MAX_VALUE
            for (i in normWords.indices) {
                for (span in 1..minOf(nameWords + 2, 4, normWords.size - i)) {
                    val heard = normWords.subList(i, i + span).joinToString("")
                    if (heard.isEmpty()) continue
                    if (heard.length > target.length * 2 + 2) break
                    val score = closeness(heard, target, targetKey, keys)
                    if (score < best) best = score
                }
            }
            if (best != Int.MAX_VALUE) scored += name.trim() to best
        }
        return scored.sortedBy { it.second }.take(max).map { it.first }
    }

    /** Lower is closer; [Int.MAX_VALUE] means not a plausible sound-alike. */
    private fun closeness(heard: String, target: String, targetKey: String, keys: HashMap<String, String>): Int {
        if (heard == target) return 0 // right sounds, wrong case or split ("github", "hear say")
        if (!soundsAlike(heard.first(), target.first())) return Int.MAX_VALUE
        val d = editDistance(heard, target)
        val limit = when {
            target.length <= 3 -> 0
            target.length <= 5 -> 1
            else -> maxOf(1, (target.length * 0.3).toInt())
        }
        if (d <= limit) return d
        // Spelled differently but sounds the same ("mug the" / "Mugdha", "cash agra" / "Kushagra").
        val k = keys.getOrPut(heard) { key(heard) }
        if (targetKey.length >= 3 && heard.length >= target.length * 0.6 && heard.length <= target.length * 1.6 + 1 && k == targetKey) return d + 1
        return Int.MAX_VALUE
    }

    private fun spelledExactly(text: String, name: String): Boolean {
        if (name.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(name, from)
            if (at < 0) return false
            val before = text.getOrNull(at - 1); val after = text.getOrNull(at + name.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = at + 1
        }
    }

    /**
     * A rough sound key: similar consonants merged, vowels after the first letter dropped,
     * repeats collapsed. "Kushagra" and "cash agra" both become "kxkr".
     */
    internal fun key(s: String): String {
        var t = s.lowercase().filter { it.isLetter() }
        if (t.isEmpty()) return ""
        t = t.replace("ph", "f").replace("ck", "k").replace("sh", "x").replace("ch", "x")
            .replace("gh", "").replace("th", "t").replace("dh", "d").replace("bh", "b").replace("kh", "k")
            .replace(Regex("c(?=[eiy])"), "s")
        if (t.startsWith("kn") || t.startsWith("wr")) t = t.drop(1)
        val out = StringBuilder()
        for ((i, ch) in t.withIndex()) {
            val m = when (ch) {
                'c', 'k', 'q', 'g' -> 'k'
                'z', 's' -> 's'
                'v', 'f' -> 'f'
                'd', 't' -> 't'
                'b', 'p' -> 'p'
                'm', 'n' -> 'n'
                'j' -> 'j'
                'a', 'e', 'i', 'o', 'u', 'y', 'h', 'w' -> if (i == 0) 'a' else null
                else -> ch
            } ?: continue
            if (out.isEmpty() || out.last() != m) out.append(m)
        }
        return out.toString()
    }

    fun soundsAlike(x: Char, y: Char): Boolean {
        if (x == y) return true
        val groups = listOf("ckqg", "sz", "fv", "bp", "dt", "gj", "aeiouy", "mn", "wv", "xs")
        return groups.any { x in it && y in it }
    }

    internal fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
