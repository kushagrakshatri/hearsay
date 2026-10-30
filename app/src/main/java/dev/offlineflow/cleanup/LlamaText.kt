package dev.offlineflow.cleanup

import dev.offlineflow.llm.LlamaEngine
import dev.offlineflow.llm.TextEngine

/**
 * llama.cpp (GGUF, CPU) behind [TextEngine]. Prompts are chat-formatted here by hand ([format]),
 * and each prepared prompt's evaluated state is snapshotted, in memory and in [cache] on disk.
 */
internal class LlamaText(
    private val llm: LlamaEngine,
    private val format: LlmCleaner.Format,
    private val cache: LlmCleaner.PrefixCache?,
) : TextEngine {
    override val backend = "llama.cpp CPU"
    override val preparedCount: Int get() = llm.prefixCount

    override fun prepare(system: String, examples: List<String>): Int {
        val text = format.prefix(system, examples)
        return llm.addPrefix(text, cache?.fileFor(text))
    }

    override fun complete(handle: Int, note: String?, user: String, maxTokens: Int): String =
        llm.complete(handle, note?.let(format::note).orEmpty() + format.turn(user), maxTokens)

    override fun close() = llm.close()
}
