package dev.offlineflow.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * A .litertlm model running through Google's LiteRT-LM, on the GPU (OpenCL) when the phone
 * supports it, else on the CPU (XNNPACK).
 *
 * One prepared prompt at a time is kept as a [Conversation] whose system prompt and few-shot
 * examples are prefilled ([ConversationConfig.prefillPrefaceOnInit]); each call clones it, so only
 * the new turn is read. Switching prompts (another style, command mode) closes it and prefills the
 * new one on first use or [warm]. A failed copy prepares the prompt again next time; copying stops
 * for good only after a few failures in a row ([ClonePolicy]). A call that can't copy (a names
 * note, copying off) opens a fresh conversation and reads the whole prompt (slower).
 */
class LiteRtEngine private constructor(
    private val engine: Engine,
    override val backend: String,
    /** The model's multi-token-prediction drafter is on (speculative decoding). */
    val mtp: Boolean,
    /** MTP state in plain words: "MTP on", "MTP off (PowerVR GPU)", … */
    val mtpStatus: String,
) : TextEngine {
    private var last: RunStats? = null
    override val lastRun: RunStats? get() = last
    private var benchmark = true

    private class Prompt(val system: String, val examples: List<String>)

    private val prompts = ArrayList<Prompt>()

    /** The one prefilled conversation, and the prompt it holds (-1: none). */
    private var base: Conversation? = null
    private var baseHandle = -1
    private val policy = ClonePolicy()

    override val preparedCount: Int get() = prompts.size

    override fun prepare(system: String, examples: List<String>): Int {
        prompts += Prompt(system, examples)
        val handle = prompts.size - 1
        baseFor(handle) // prefill now, so the first call doesn't pay for it
        return handle
    }

    override fun warm(handle: Int) { baseFor(handle) }

    /**
     * The prefilled conversation for [handle], replacing the one kept for another prompt; null
     * when copying is off or the prompt couldn't be prefilled.
     */
    private fun baseFor(handle: Int): Conversation? {
        if (!policy.on) return null
        if (baseHandle == handle) base?.let { return it }
        closeBase()
        val p = prompts[handle]
        return try {
            open(p.system, p.examples, prefill = true).also { base = it; baseHandle = handle }
        } catch (t: Throwable) {
            Log.w(TAG, "can't prefill the prompt; this call reads it whole", t)
            failed(FullPromptWhy.NO_BASE)
            null
        }
    }

    override fun complete(handle: Int, note: String?, user: String, maxTokens: Int): String {
        val p = prompts[handle]
        // The note changes the system prompt, so it can't reuse the prefilled one. It's rare:
        // only when a name on screen or a newly learned word sounds like something in the transcript.
        var why: FullPromptWhy? = if (note != null) FullPromptWhy.NAMES_NOTE else null
        var copy: Conversation? = null
        if (note == null) {
            val b = baseFor(handle)
            if (b == null) why = if (policy.on) FullPromptWhy.NO_BASE else policy.lastFailure ?: FullPromptWhy.NO_BASE
            else try {
                copy = b.clone()
            } catch (t: Throwable) {
                Log.w(TAG, "copying the prefilled prompt failed; preparing it again next call", t)
                closeBase()
                failed(FullPromptWhy.CLONE_ERROR)
                why = FullPromptWhy.CLONE_ERROR
            }
        }
        if (copy != null) {
            try {
                return send(copy, user, maxTokens, why = null, retried = false).also { policy.succeeded() }
            } catch (t: Throwable) {
                // Seen with MTP on a copy ("Failed to register input tensor buffer"), now and then:
                // redo the call once on a fresh conversation before the caller falls back to rules.
                Log.w(TAG, "writing on the copied prompt failed; retrying once with the whole prompt", t)
                failed(FullPromptWhy.CLONE_ERROR)
                return send(fresh(p, null), user, maxTokens, FullPromptWhy.CLONE_ERROR, retried = true)
            }
        }
        return send(fresh(p, note), user, maxTokens, why ?: FullPromptWhy.NO_BASE, retried = false)
    }

    /** A new conversation that reads the whole prompt (plus [note]) on its first message. */
    private fun fresh(p: Prompt, note: String?): Conversation {
        val system = if (note == null) p.system else p.system + "\n" + note
        return try {
            open(system, p.examples, prefill = false)
        } catch (t: Throwable) {
            // Some builds allow one conversation at a time: drop the prefilled one and retry.
            if (base == null) throw t
            Log.w(TAG, "can't open a conversation beside the prefilled one; closing it and retrying", t)
            closeBase()
            failed(FullPromptWhy.OPEN_FAILED)
            open(system, p.examples, prefill = false)
        }
    }

    /** Sends [user] on [conv] (closing it after), recording what the call did. */
    private fun send(conv: Conversation, user: String, maxTokens: Int, why: FullPromptWhy?, retried: Boolean): String {
        try {
            val t0 = System.nanoTime()
            val out = conv.sendMessage(Message.user(user), maxOutputToken = maxTokens).toString()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val s = stats(conv, out, ms, why, retried)
            last = s
            Log.i(TAG, "complete: ${out.length} chars in $ms ms ($backend, MTP ${if (mtp) "on" else "off"}); " +
                (if (why == null) "copied prompt" else "whole prompt (${why.words}" + (if (!policy.on) ", copying off" else "") + ")") +
                (if (retried) ", retried" else "") +
                ", read ${s.promptTokens ?: "?"} tokens in ${s.promptMs ?: "?"} ms" +
                ", ${s.tokens} tokens at ${s.tokensPerSec?.let { "%.1f".format(it) } ?: "?"} tok/s" + (if (s.approx) " (estimated)" else ""))
            return out
        } finally {
            runCatching { conv.close() }
        }
    }

    /**
     * Token counts from LiteRT-LM's benchmark info when the runtime reports them; otherwise an
     * estimate from the text (~4 characters a token) over the whole call, marked approximate.
     */
    @OptIn(ExperimentalApi::class)
    private fun stats(conv: Conversation, out: String, ms: Long, why: FullPromptWhy?, retried: Boolean): RunStats {
        val engineName = if (backend.endsWith("GPU")) "LiteRT-LM graphics chip" else "LiteRT-LM processor"
        // Benchmark info is off unless the runtime collects it: after one failure, don't ask again.
        val bench = if (!benchmark) null else runCatching { conv.getBenchmarkInfo() }
            .onFailure { benchmark = false; Log.w(TAG, "no benchmark info from LiteRT-LM; speeds are estimates", it) }.getOrNull()
        val promptTokens = bench?.lastPrefillTokenCount?.takeIf { it > 0 }
        val prefillTps = bench?.lastPrefillTokensPerSecond ?: 0.0
        val promptMs = when {
            promptTokens != null && prefillTps > 0.0 -> (promptTokens * 1000.0 / prefillTps).toLong()
            bench != null && bench.timeToFirstTokenInSecond > 0.0 -> (bench.timeToFirstTokenInSecond * 1000).toLong()
            else -> null
        }
        val n = bench?.lastDecodeTokenCount ?: 0
        val tps = bench?.lastDecodeTokensPerSecond ?: 0.0
        val r = if (n > 0 && tps > 0.0) RunStats(engineName, mtpStatus, ms, n, tps)
        else {
            val est = (out.length + 3) / 4
            RunStats(engineName, mtpStatus, ms, est, if (est > 0 && ms > 0) est * 1000.0 / ms else null, approx = true)
        }
        return r.copy(
            promptTokens = promptTokens, promptMs = promptMs,
            reusedPrompt = why == null, fullPromptWhy = why,
            copyingOff = !policy.on, retried = retried,
        )
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

    /** Records a copying failure; after a few in a row, copying is off for the engine's life. */
    private fun failed(why: FullPromptWhy) {
        if (policy.failed(why)) {
            Log.w(TAG, "copying the prefilled prompt is off for good: ${policy.failuresInARow} failures in a row (last: ${why.words})")
            closeBase()
        }
    }

    private fun closeBase() {
        base?.let { runCatching { it.close() } }
        base = null; baseHandle = -1
    }

    override fun close() {
        closeBase()
        runCatching { engine.close() }
    }

    companion object {
        private const val TAG = "hearsay-litert"

        /** Whether the .litertlm file carries an MTP drafter section; null when it can't be read. */
        private fun hasDrafter(model: File): Boolean? = try {
            ModelInfo.from(model.absolutePath).use { info ->
                (info as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport()
            }.also { Log.i(TAG, "${model.name}: MTP drafter in model: ${it ?: "?"}") }
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't read model info for ${model.name}", t); null
        }
        /**
         * Asks LiteRT-LM to measure each call (prompt tokens read, decode speed) for the "Last
         * cleanup" readout. Process-wide; read when engines and conversations are made.
         */
        @OptIn(ExperimentalApi::class)
        private fun enableBenchmark() {
            runCatching { ExperimentalFlags.enableBenchmark = true }
                .onFailure { Log.w(TAG, "couldn't turn on LiteRT-LM benchmark info", it) }
        }

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
            val drafter = hasDrafter(model)
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
                // MTP: on for GPUs except PowerVR (it slows decode there), on the CPU per the setting.
                // An MTP start that fails is retried once without it before giving up on this backend.
                val wantMtp = drafter != false && (if (gpu) !Speculation.powerVrGpu else Speculation.mtpOnCpu)
                val why = when {
                    drafter == false -> "no drafter in model"
                    gpu && Speculation.powerVrGpu -> "PowerVR GPU"
                    !gpu && !Speculation.mtpOnCpu -> "turned off for processor"
                    else -> ""
                }
                for (mtp in if (wantMtp) listOf(true, false) else listOf(false)) {
                    var engine: Engine? = null
                    try {
                        enableBenchmark()
                        engine = Engine(
                            EngineConfig(
                                modelPath = model.absolutePath,
                                backend = if (gpu) Backend.GPU() else Backend.CPU(threadCount = threads),
                                maxNumTokens = 2048,
                                cacheDir = cacheDir.absolutePath,
                                speculativeDecodingConfig = SpeculativeDecodingConfig(
                                    if (mtp) SpeculativeDecodingMode.ON else SpeculativeDecodingMode.OFF
                                ),
                            )
                        )
                        engine.initialize()
                        trying.delete()
                        Log.i(TAG, "${model.name} loaded on $name, MTP ${if (mtp) "on" else "off"}" +
                            (if (!mtp && why.isNotEmpty()) " ($why)" else if (!mtp && wantMtp) " (MTP start failed)" else ""))
                        val status = when {
                            mtp -> "MTP on"
                            why.isNotEmpty() -> "MTP off ($why)"
                            wantMtp -> "MTP off (didn't start)"
                            else -> "MTP off"
                        }
                        return LiteRtEngine(engine, "LiteRT-LM $name", mtp, status)
                    } catch (t: Throwable) {
                        runCatching { engine?.close() }
                        if (mtp) { Log.w(TAG, "${model.name} failed to start on $name with MTP; retrying without", t); continue }
                        trying.delete()
                        Log.w(TAG, "${model.name} failed to start on $name", t)
                        if (gpu) bad.writeText(t.toString().take(500)) // no usable GPU here: go straight to CPU next time
                        if (first == null) first = t
                    }
                }
            }
            throw IllegalStateException("LiteRT-LM couldn't start ${model.name}", first)
        }
    }
}
