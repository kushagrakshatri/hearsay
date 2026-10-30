package dev.offlineflow.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * A .litertlm model running through Google's LiteRT-LM, on the GPU (OpenCL) when the phone
 * supports it, else on the CPU (XNNPACK).
 *
 * Each prepared prompt is a [Conversation] whose system prompt and few-shot examples are
 * prefilled once ([ConversationConfig.prefillPrefaceOnInit]); every call clones it, so only the
 * new turn is evaluated. The chat template comes from the model file, so prompts here are plain
 * text. If cloning isn't supported, each call opens a fresh conversation instead (slower).
 */
class LiteRtEngine private constructor(private val engine: Engine, override val backend: String) : TextEngine {
    private class Prompt(val system: String, val examples: List<String>, var base: Conversation?)

    private val prompts = ArrayList<Prompt>()
    private var cloning = true

    override val preparedCount: Int get() = prompts.size

    override fun prepare(system: String, examples: List<String>): Int {
        val p = Prompt(system, examples, null)
        if (cloning) {
            try {
                p.base = open(system, examples, prefill = true)
            } catch (t: Throwable) {
                Log.w(TAG, "can't keep a prefilled conversation; opening one per call", t)
                stopCloning()
            }
        }
        prompts += p
        return prompts.size - 1
    }

    override fun complete(handle: Int, note: String?, user: String, maxTokens: Int): String {
        val p = prompts[handle]
        // The note changes the system prompt, so it can't reuse the prefilled one. It's rare:
        // only when a name on screen sounds like something in the transcript.
        val cloned = if (note == null) p.base?.let { base ->
            try { base.clone() } catch (t: Throwable) { Log.w(TAG, "clone failed", t); stopCloning(); null }
        } else null
        val conv: Conversation = cloned ?: run {
            val system = if (note == null) p.system else p.system + "\n" + note
            try {
                open(system, p.examples, prefill = false)
            } catch (t: Throwable) {
                // Some builds allow one conversation at a time: drop the cached ones and retry.
                if (prompts.none { it.base != null }) throw t
                stopCloning()
                open(system, p.examples, prefill = false)
            }
        }
        try {
            return conv.sendMessage(Message.user(user), maxOutputToken = maxTokens).toString()
        } finally {
            runCatching { conv.close() }
        }
    }

    private fun open(system: String, examples: List<String>, prefill: Boolean): Conversation =
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = examples.chunked(2).flatMap { (u, a) -> listOf(Message.user(u), Message.model(a)) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                prefillPrefaceOnInit = prefill,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
        )

    private fun stopCloning() {
        cloning = false
        for (p in prompts) { runCatching { p.base?.close() }; p.base = null }
    }

    override fun close() {
        stopCloning()
        runCatching { engine.close() }
    }

    companion object {
        private const val TAG = "hearsay-litert"
        /** Bumped with the LiteRT-LM version so a newer runtime gets to try the GPU again. */
        private const val RUNTIME = "0.19.0"

        /**
         * Loads [model] on the GPU, falling back to the CPU. [cacheDir] lets LiteRT-LM keep
         * compiled kernels and weight layouts, which speeds up later loads. [stateDir] remembers
         * a backend that failed or crashed the process while starting, so it isn't tried again.
         */
        fun load(model: File, cacheDir: File, stateDir: File, threads: Int): LiteRtEngine {
            require(model.exists()) { "Model not found: ${model.path}" }
            cacheDir.mkdirs(); stateDir.mkdirs()
            var first: Throwable? = null
            for (gpu in listOf(true, false)) {
                val name = if (gpu) "GPU" else "CPU"
                val tag = "litertlm-$RUNTIME-${model.name}-$name"
                val bad = File(stateDir, "$tag.bad")
                val trying = File(stateDir, "$tag.trying")
                if (bad.exists()) continue
                // A start that never finished: the process died in it (likely a driver crash).
                // Twice in a row and this backend is skipped from now on.
                val unfinished = runCatching { trying.readText().trim().toInt() }.getOrDefault(0)
                if (unfinished >= 2) { bad.writeText("crashed while starting"); trying.delete(); continue }
                trying.writeText((unfinished + 1).toString())
                var engine: Engine? = null
                try {
                    engine = Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = if (gpu) Backend.GPU() else Backend.CPU(threadCount = threads),
                            maxNumTokens = 2048,
                            cacheDir = cacheDir.absolutePath,
                        )
                    )
                    engine.initialize()
                    trying.delete()
                    Log.i(TAG, "${model.name} loaded on $name")
                    return LiteRtEngine(engine, "LiteRT-LM $name")
                } catch (t: Throwable) {
                    trying.delete()
                    runCatching { engine?.close() }
                    Log.w(TAG, "${model.name} failed to start on $name", t)
                    if (gpu) bad.writeText(t.toString().take(500)) // no usable GPU here: go straight to CPU next time
                    if (first == null) first = t
                }
            }
            throw IllegalStateException("LiteRT-LM couldn't start ${model.name}", first)
        }
    }
}
