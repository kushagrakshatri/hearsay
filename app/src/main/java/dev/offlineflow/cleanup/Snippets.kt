package dev.offlineflow.cleanup

import android.content.Context

/**
 * Voice snippets, one per line:
 *   my email = kushagra@example.com
 *   my address = 640 Epic Way #583\nSan Jose, CA 95134
 * Say just the trigger ("my email") and the text is inserted exactly as written, with no
 * cleanup or AI rewrite. "\n" in the text becomes a line break. Stored on-device only.
 */
class Snippets(val entries: List<Pair<String, String>>) {
    private val byTrigger: Map<String, String> = entries.associate { norm(it.first) to it.second }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Returns the snippet text when the whole utterance is a trigger, allowing the ASR's
     * punctuation and case, and an optional leading "insert", "paste" or "snippet".
     */
    fun match(transcript: String): String? {
        if (isEmpty) return null
        val heard = norm(transcript)
        if (heard.isEmpty()) return null
        byTrigger[heard]?.let { return it }
        val stripped = LEAD.replaceFirst(heard, "")
        return if (stripped != heard) byTrigger[stripped] else null
    }

    companion object {
        private const val PREFS = "hearsay"
        private const val KEY = "snippets"
        private val LEAD = Regex("^(?:insert|paste|snippet|type)\\s+")

        fun parse(raw: String): Snippets {
            val out = ArrayList<Pair<String, String>>()
            for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                val trigger = line.substring(0, i).trim()
                val text = line.substring(i + 1).trim().replace("\\n", "\n")
                if (norm(trigger).isNotEmpty() && text.isNotEmpty()) out += trigger to text
            }
            return Snippets(out.distinctBy { norm(it.first) })
        }

        fun load(context: Context): Snippets = parse(text(context))

        fun text(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

        fun save(context: Context, raw: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw.trim()).apply()

        /** Lowercase words joined by single spaces; punctuation dropped, hyphens become spaces. */
        internal fun norm(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}']+"), " ").replace("'", "").trim()
    }
}
