package dev.offlineflow.llm

import java.io.File

/**
 * A small instruction-tuned LLM (GGUF) running on-device through llama.cpp.
 * Fixed prompt prefixes (already chat-formatted) are evaluated once with [addPrefix] and
 * snapshotted, so each [complete] call only pays for its own suffix. Not thread-safe.
 */
class LlamaEngine private constructor(private var handle: Long) : AutoCloseable {

    /** How many prefixes have been cached; each keeps a state snapshot in memory. */
    var prefixCount = 0
        private set

    /**
     * Decodes and caches [prefix]; returns its id for [complete]. With a [cache] file, the
     * evaluated state is read from it when it holds exactly this prefix, and saved to it otherwise,
     * so the next process doesn't decode the prefix again.
     */
    fun addPrefix(prefix: String, cache: File? = null): Int {
        check(handle != 0L) { "LlamaEngine is closed" }
        cache?.parentFile?.mkdirs()
        val id = nativeAddPrefix(handle, prefix, cache?.absolutePath ?: "")
        check(id >= 0) { "Failed to evaluate prompt prefix" }
        prefixCount++
        return id
    }

    /**
     * Greedy completion after restoring cached prefix [prefixId]. Blocking; off the main thread.
     * [speculative]: prompt-lookup drafts from the suffix and prefix (same output, fewer decode
     * passes); falls back to plain decoding on any error.
     */
    fun complete(prefixId: Int, suffix: String, maxTokens: Int, speculative: Boolean = Speculation.draftFromTranscript): String {
        check(handle != 0L) { "LlamaEngine is closed" }
        return nativeComplete(handle, prefixId, suffix, maxTokens, speculative)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeAddPrefix(handle: Long, prefix: String, cache: String): Int
    private external fun nativeComplete(handle: Long, prefix: Int, suffix: String, maxTokens: Int, speculative: Boolean): String
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("hearsayllm") }

        /** [specDraft]: draft length speculative decoding is set up for (0 = never use it). */
        fun load(model: File, threads: Int, specDraft: Int = Speculation.LLAMA_DRAFT): LlamaEngine {
            require(model.exists()) { "Model not found: ${model.path}" }
            val h = nativeInit(model.absolutePath, threads, specDraft)
            check(h != 0L) { "Failed to load ${model.name}" }
            return LlamaEngine(h)
        }

        @JvmStatic private external fun nativeInit(path: String, threads: Int, specDraft: Int): Long
    }
}
