package dev.offlineflow.llm

/**
 * How many runs of the model's fixed-size prefill graphs LiteRT-LM 0.19.0 uses to read a prompt
 * (its GetOptimizedPrefillWorkGroups, without the context-capacity limit). Each run costs about
 * the same however few of its slots hold real tokens: the graphs have a static length, so a
 * 31-token turn is computed as a full 128-token run (desktop CPU: 1, 31 and 127 tokens each ~0.4 s,
 * 129 tokens ~0.8 s). Pure logic, so it's unit tested.
 */
object PrefillPasses {
    /** Prefill graph lengths in the bundled Gemma 4 E2B .litertlm: prefill_1024 and prefill_128. */
    val GEMMA4_E2B = listOf(1024, 128)

    /**
     * Runs needed for [tokens]. [greedy] is LiteRT-LM's CPU rule (fill with the biggest graph
     * that fits, then smaller ones); the GPU rule instead covers a remainder of at least half a
     * graph with one more run of that graph rather than several smaller ones.
     */
    fun count(tokens: Int, sizes: List<Int> = GEMMA4_E2B, greedy: Boolean = false): Int {
        if (tokens <= 0 || sizes.isEmpty()) return 0
        val sorted = sizes.distinct().sortedDescending()
        var left = tokens
        var runs = 0
        for ((i, len) in sorted.withIndex()) {
            if (left <= 0) break
            runs += left / len
            left %= len
            if (left == 0) break
            val next = sorted.getOrNull(i + 1)
            if (next == null) { runs++; left = 0; break }
            if (greedy) continue
            if (next * 2 >= len && left <= next) continue
            if (left * 2 >= len) { runs++; left = 0; break }
        }
        return runs
    }
}
