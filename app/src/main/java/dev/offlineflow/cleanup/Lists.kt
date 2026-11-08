package dev.offlineflow.cleanup

/**
 * Turns a spoken enumeration ("my priorities are first …, second …, and third …") into a
 * numbered list. Used after models that clean the words but keep them in one sentence
 * (Emberon-2). It needs "first" and then "second" at clause starts; plain comma lists
 * ("eggs, milk, coffee") stay a sentence, since guessing those goes wrong too often.
 */
object Lists {
    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val MARKER = Regex(
        "(?i)\\b(?:(and|then)\\s+)?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|finally|lastly)(?:ly)?\\b(?!\\s+of\\b)[,:]?\\s*"
    )

    private class Mark(val from: Int, val to: Int, val rank: Int)

    fun format(text: String): String {
        if ('\n' in text) return text
        val marks = ArrayList<Mark>()
        for (m in MARKER.findAll(text)) {
            // Only at a clause start: the text's start, or after : ; , . ! ?
            val before = text.substring(0, m.range.first).trimEnd()
            if (before.isNotEmpty() && before.last() !in ":;,.!?") continue
            val word = m.groupValues[2].lowercase()
            val rank = if (word == "finally" || word == "lastly") -1 else ORDINALS.indexOf(word) + 1
            val expected = marks.size + 1
            when {
                marks.isEmpty() && rank != 1 -> continue
                rank == expected -> marks += Mark(m.range.first, m.range.last + 1, rank)
                rank == -1 && marks.size >= 2 -> { marks += Mark(m.range.first, m.range.last + 1, rank); break }
                marks.isNotEmpty() -> break
            }
        }
        if (marks.size < 2) return text

        val intro = text.substring(0, marks.first().from).trim().trimEnd(',', ';', ':').trim()
        val items = ArrayList<String>()
        var rest: String? = null
        for ((i, m) in marks.withIndex()) {
            var item = text.substring(m.to, marks.getOrNull(i + 1)?.from ?: text.length).trim()
            if (i == marks.lastIndex) {
                // A new sentence after the last item is not part of the list.
                Regex("[.!?]\\s+(?=[A-Z])").find(item)?.let { end ->
                    rest = item.substring(end.range.last + 1).trim()
                    item = item.substring(0, end.range.first)
                }
            }
            item = item.trimEnd(' ', ',', ';', '.').removeSuffix(" and").removeSuffix(" then").trimEnd(' ', ',', ';')
            if (item.isEmpty()) return text
            items += item.replaceFirstChar { it.uppercase() }
        }
        return buildString {
            if (intro.isNotEmpty()) {
                append(intro.replaceFirstChar { it.uppercase() })
                if (intro.last() !in "?!") append(':')
                append('\n')
            }
            items.forEachIndexed { i, s -> if (i > 0) append('\n'); append(i + 1).append(". ").append(s) }
            rest?.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
        }
    }
}
