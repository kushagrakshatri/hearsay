package dev.offlineflow.cleanup

/**
 * Mends the seams between pieces closed at pauses. Parakeet transcribes each piece on its own and
 * ends it like a sentence ("…while you talked." + "For speech or cleanup, …"), even when the
 * speaker only paused mid-sentence. At a seam where the next piece clearly carries on the
 * sentence, the period goes and the next piece's first word is lowercased (never a name, "I" or
 * an acronym). Used where live speech joins its pieces (core/LiveSpeech) and where live cleanup
 * joins its pieces and cleaned stretches ([Pieces]), so the raw and cleaned text agree.
 *
 * Only plain periods move: "?" and "!" stay, and so does a period that may end a real sentence.
 * A clause that may belong to the sentence before ("…seconds. After you let go, I can't…") is
 * left where it is: "It was late. After the show, we took a cab." reads the same way.
 *
 * Python twin, measured on chunked Parakeet: tools/eval/joins/joins.py.
 */
object Joins {
    /** Everyday words that may be lowercased; [Words] fills this in when it loads. */
    @Volatile var common: Set<String> = emptySet()

    // A sentence (almost) never ends on these, so a period after one marks a pause, not an end.
    private val NEVER_END = words(
        "the a an my your our their its and or but nor of with from than because whether whose until very " +
            "into onto upon via plus versus",
    )
    // These can end a sentence ("log in", "I think so", "here it is"); they count only when the
    // piece ends without a period, i.e. the recognizer itself heard the sentence go on.
    private val WEAK_END = NEVER_END + words(
        "to at in on for by about that so if when while before after since is are was were be been will would " +
            "could should can may might must has have had do does did not this these those who what which where " +
            "how his her him them us me you we they it as like just really also",
    )
    // "to" ends a sentence after these ("I'd love to.", "I have to.").
    private val TO_ENDS_AFTER = words(
        "want wants wanted love like liked have has had need needs needed going got try tried used ought " +
            "supposed able glad happy mean meant plan planned hope hoped",
    )
    // A piece starting with one of these carries on the sentence before it ("…the line | for speech").
    private val CONT_START = words("for of than to because which including nor")
    // Object pronouns don't open a sentence; they carry one on when the piece before was left open.
    private val OPEN_START = words("us them him whom")
    // A preposition ending a piece before a day, month or number ("on. Saturday", "at. Seven").
    private val WHEN_PREP = words("on at by until till before after in from")
    private val WHEN_WORDS = words(
        "monday tuesday wednesday thursday friday saturday sunday tomorrow tonight noon midnight january " +
            "february march april may june july august september october november december one two three four " +
            "five six seven eight nine ten eleven twelve",
    )
    // Sentence openers that start with a CONT_START word.
    private val OPENER = Regex(
        "^(?:for (?:example|instance|now|sure|one|real|the record|what|some reason|me|us|years|once)\\b|" +
            "to (?:be|me|us|my|our|start|begin|sum|summarize|recap|clarify)\\b|of course\\b|because of that\\b)",
        RegexOption.IGNORE_CASE,
    )
    // "For lunch we had pizza": a fronted phrase followed by its own subject opens a new sentence.
    private val SUBJECT = Regex("\\b(?:i|we|you|he|she|they|it|there)(?:['\u2019](?:m|re|ve|ll|d|s))?\\b", RegexOption.IGNORE_CASE)
    private val WHICH_QUESTION = Regex("^which\\b.*\\?$", RegexOption.IGNORE_CASE)
    private val I_WORDS = Regex("I(?:['\u2019](?:m|d|ll|ve|s))?")
    private val WORD = Regex("[A-Za-z][A-Za-z'\u2019]*")
    private val ENDS_LETTER = Regex("[A-Za-z]$")
    private val ENDS_SENTENCE = Regex("[.!?:;\u2026)\"'\u201d]\\s*$")

    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toHashSet()
    private fun norm(w: String) = w.lowercase().replace('\u2019', '\'')

    /** True when [next] carries on the sentence [prev] left open at a pause. */
    fun continues(prev: String, next: String): Boolean {
        val p = prev.trimEnd()
        val n = next.trimStart()
        if (p.isEmpty() || n.isEmpty() || p.last() in "?!:;\"')\u201d\u2026") return false
        if (!n[0].isLetterOrDigit() || WHICH_QUESTION.containsMatchIn(n.trimEnd())) return false
        if (!ENDS_LETTER.containsMatchIn(p.trimEnd('.', ','))) return false
        val tail = WORD.findAll(p).map { norm(it.value) }.toList().takeLast(2)
        val last = tail.lastOrNull() ?: return false
        val openEnd = p.last() != '.'
        val toEnds = last == "to" && tail.size > 1 && tail[0] in TO_ENDS_AFTER
        if (last in NEVER_END || (openEnd && last in WEAK_END && !toEnds)) return true
        val first = WORD.find(n)?.takeIf { it.range.first == 0 }?.value?.let(::norm) ?: ""
        if (openEnd && (n[0].isLowerCase() || first in OPEN_START)) return true
        if (last in WHEN_PREP && (first in WHEN_WORDS || n[0].isDigit())) return true
        if (first in CONT_START && !OPENER.containsMatchIn(n) && ',' !in n.split(' ').take(3).joinToString(" ")) {
            return !((first == "for" || first == "to") && SUBJECT.containsMatchIn(n.substringBefore(',')))
        }
        return false
    }

    /** [next] with its first word lowercased when it is an everyday word: never a name, "I" or an acronym. */
    fun lowercaseFirst(next: String, common: Set<String> = this.common): String {
        val m = WORD.find(next)?.takeIf { it.range.first == 0 } ?: return next
        val w = m.value
        if (I_WORDS.matches(w) || w.drop(1).any { it.isUpperCase() }) return next
        val low = norm(w)
        return if (low in common || low in WEAK_END || low in CONT_START) next.replaceFirstChar { it.lowercaseChar() } else next
    }

    /**
     * [parts] joined with spaces, mending each seam where the next part carries on the sentence.
     * With [period], a part that doesn't carry on and was left unpunctuated gets a period.
     */
    fun join(parts: List<String>, period: Boolean = false, common: Set<String> = this.common): String {
        val sb = StringBuilder()
        for (part in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (sb.isEmpty()) { sb.append(part); continue }
            val out = sb.toString()
            if (continues(out, part)) {
                if (out.endsWith('.') && !out.endsWith("..")) sb.setLength(sb.length - 1)
                sb.append(' ').append(lowercaseFirst(part, common))
            } else {
                if (period && !ENDS_SENTENCE.containsMatchIn(out)) sb.append('.')
                sb.append(' ').append(part)
            }
        }
        return sb.toString()
    }
}
