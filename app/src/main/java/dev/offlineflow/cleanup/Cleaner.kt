package dev.offlineflow.cleanup

/** Turns a raw transcript into ready-to-send text. M1 adds a local-LLM implementation. */
interface Cleaner {
    fun clean(raw: String): String
}

/** Deterministic fallback: drops fillers, tidies spacing, capitalization and end punctuation. */
object RulesCleaner : Cleaner {
    private val fillers = Regex("""\b(um+|uh+|erm+|hmm+)\s*""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""\[[^\]]*]|\([^)]*\)""") // whisper's [BLANK_AUDIO], (laughs)
    private val spaces = Regex("""\s+""")
    private val spaceBeforePunct = Regex("""\s+([,.!?;:])""")
    private val doubleComma = Regex(""",\s*,""")

    override fun clean(raw: String): String {
        var t = raw.replace(bracketed, " ").replace(fillers, "")
        t = t.replace(spaces, " ").replace(spaceBeforePunct, "$1").replace(doubleComma, ",").trim().trim(',').trim()
        if (t.isEmpty()) return t
        t = t.replaceFirstChar { it.uppercaseChar() }
        t = Regex("""([.!?]\s+)([a-z])""").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        t = Regex("""\bi\b""").replace(t, "I")
        if (t.last() !in ".!?") t += "."
        return t
    }

    /** For code, URLs and search: the transcript as heard, minus noise tags and one trailing period. */
    fun verbatim(raw: String): String {
        var t = raw.replace(bracketed, " ").replace(spaces, " ").trim()
        if (t.endsWith(".") && !t.endsWith("..")) t = t.dropLast(1) // ASR's sentence period, not part of a URL
        return t
    }
}
