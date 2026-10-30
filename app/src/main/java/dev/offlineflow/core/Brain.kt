package dev.offlineflow.core

import android.content.Context
import android.util.Log
import dev.offlineflow.asr.Engines
import dev.offlineflow.asr.SpeechEngine
import dev.offlineflow.cleanup.Cleaner
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.LlmCleaner
import dev.offlineflow.cleanup.RulesCleaner
import dev.offlineflow.cleanup.Style
import dev.offlineflow.cloud.CloudClient
import dev.offlineflow.cloud.CloudText
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The models, shared by the keyboard and the floating mic so the phone only ever holds one copy.
 * Speech and the LLM each run on their own single thread.
 */
object Brain {
    private const val TAG = "HearsayBrain"
    val asr = Dispatchers.Default.limitedParallelism(1)
    val llm = Dispatchers.Default.limitedParallelism(1)

    private var engine: SpeechEngine? = null
    private var engineModel: ModelManager.Model? = null
    private var cleaner: LlmCleaner? = null
    private var cleanerModel: ModelManager.Model? = null

    fun engineLoaded(m: ModelManager.Model) = engine != null && engineModel == m

    /** The engine for the selected model, (re)loading it if the choice changed. [asr] thread only. */
    fun engine(models: ModelManager): SpeechEngine {
        val want = models.selected
        engine?.let { if (engineModel == want) return it }
        engine?.close(); engine = null; engineModel = null
        return Engines.load(models, want).also { engine = it; engineModel = want }
    }

    /** The selected cleanup LLM, or RulesCleaner when off / not downloaded. [llm] thread only. */
    fun cleaner(models: ModelManager, dictionary: Dictionary): Cleaner {
        val want = models.selectedCleanup?.takeIf { models.isReady(it) }
        if (want == null) {
            cleaner?.close(); cleaner = null; cleanerModel = null
            return RulesCleaner
        }
        // The dictionary is part of the cached prompt. A few newly learned words ride in the
        // per-dictation note instead, so learning a word doesn't re-evaluate the whole prompt.
        cleaner?.takeIf { cleanerModel == want }?.let { c ->
            if (c.adopt(dictionary)) return c
            // A word was removed or many piled up: rebuild the prompts, keeping the loaded weights.
            c.withDictionary(dictionary)?.let { cleaner = it; return it }
        }
        cleaner?.close(); cleaner = null; cleanerModel = null
        if (want == failedModel) return RulesCleaner
        fun load(m: ModelManager.Model) = LlmCleaner.load(
            m, models.file(m), dictionary, models.prefixCacheDir, models.engineCacheDir, models.engineStateDir,
        )
        val loaded = try {
            load(want)
        } catch (t: Throwable) {
            // LiteRT-LM couldn't start on GPU or CPU here: use a downloaded GGUF model on llama.cpp.
            val fallback = models.fallbackCleanup()?.takeIf { want.engine == ModelManager.Engine.LITERT }
            Log.e(TAG, "${want.name} failed to load" + (fallback?.let { ", using ${it.name}" } ?: ""), t)
            try {
                fallback?.let(::load)
            } catch (t2: Throwable) {
                Log.e(TAG, "${fallback?.name} failed to load too", t2); null
            } ?: run { failedModel = want; return RulesCleaner }
        }
        Log.i(TAG, "cleanup model ${want.name} on ${loaded.backend}")
        cleaner = loaded; cleanerModel = want
        return loaded
    }

    /** A cleanup model that failed to load in this process; not retried until another is picked. */
    private var failedModel: ModelManager.Model? = null

    /** Where the loaded cleanup model runs ("LiteRT-LM GPU", "llama.cpp CPU"), or null when none is loaded. */
    val cleanerBackend: String? get() = cleaner?.backend

    /** The selected cleanup model is in memory (or none is selected, so there's nothing to load). */
    fun cleanerLoaded(models: ModelManager) = models.selectedCleanup.let { it == null || (cleaner != null && cleanerModel == it) }

    suspend fun releaseLlm() = withContext(llm) {
        cleaner?.close(); cleaner = null; cleanerModel = null
    }

    suspend fun releaseAsr() = withContext(asr) {
        engine?.close(); engine = null; engineModel = null
    }

    // ------------------------------------------------------------------ one-call helpers (floating mic)

    /** Audio → raw text: cloud speech when the user turned it on (falling back to local), else local. */
    suspend fun transcribe(ctx: Context, models: ModelManager, audio: FloatArray): String {
        val cloud = CloudClient.from(ctx)?.takeIf { CloudClient.speechOn(ctx) }
        if (cloud != null) {
            try {
                return withContext(Dispatchers.IO) { cloud.transcribe(audio, dev.offlineflow.Prefs.cloudSpeechModel(ctx)) }
            } catch (t: Throwable) {
                if (!models.isReady(models.selected)) throw t
                Log.w(TAG, "cloud transcription failed, using on-device", t)
            }
        }
        return withContext(asr) { engine(models).transcribe(audio) }
    }

    class Cleaned(val text: String, val usedLlm: Boolean, val cloudNote: String?)

    /**
     * Raw text → cleaned text in [style]: ChatGPT / own key first when on, then the local LLM, then rules.
     * [names] are names seen on screen for this dictation: exact sound-alike fixes plus a hint to the LLM.
     */
    suspend fun clean(ctx: Context, models: ModelManager, raw: String, style: Style, names: List<String> = emptyList()): Cleaned {
        val saved = Dictionary.load(ctx)
        val screen = Dictionary.exact(names)
        fun fix(t: String) = screen.apply(saved.apply(t))
        val heard = fix(raw)
        if (style == Style.VERBATIM) return Cleaned(RulesCleaner.verbatim(heard), false, null)
        var note: String? = null
        suspend fun local() = withContext(llm) {
            when (val c = cleaner(models, saved)) {
                is LlmCleaner -> fix(c.clean(heard, style, names)).also { dev.offlineflow.Prefs.recordCleanup(ctx, c.lastStatus) }
                else -> fix(rules(heard, style))
            }
        }
        val usedLlm = models.selectedCleanup?.let { models.isReady(it) } == true || CloudText.on(ctx)
        val text = try {
            if (CloudText.on(ctx)) try {
                withContext(Dispatchers.IO) {
                    fix(LlmCleaner.cleanWithOrThrow(heard, style) { input, words ->
                        CloudText.chat(ctx, LlmCleaner.cleanupMessages(style, saved.spellings, input, names), words * 2 + 32)
                    })
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cloud cleanup failed, using on-device", t)
                note = t.message
                local()
            } else local()
        } catch (t: Throwable) {
            Log.e(TAG, "cleanup failed, using rules", t)
            fix(rules(heard, style))
        }
        return Cleaned(text, usedLlm, note)
    }

    /** Applies a spoken edit to [target]; null when it couldn't. */
    suspend fun edit(ctx: Context, models: ModelManager, target: String, instruction: String): String? {
        suspend fun local() = withContext(llm) {
            (cleaner(models, Dictionary.load(ctx)) as? LlmCleaner)?.edit(target.trim(), instruction)
        }
        return try {
            if (CloudText.on(ctx)) try {
                withContext(Dispatchers.IO) {
                    LlmCleaner.editWith(target.trim()) { max -> CloudText.chat(ctx, LlmCleaner.editMessages(target.trim(), instruction), max) }
                        ?: error("cloud edit returned nothing usable")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cloud command failed, using on-device", t); local()
            } else local()
        } catch (t: Throwable) {
            Log.e(TAG, "command failed", t); null
        }
    }

    fun cleanupAvailable(ctx: Context, models: ModelManager) =
        models.selectedCleanup?.let { models.isReady(it) } == true || CloudText.on(ctx)

    fun rules(text: String, s: Style): String {
        val t = RulesCleaner.clean(text)
        return if (s == Style.CASUAL && t.endsWith(".") && t.count { it in ".!?" } == 1) t.dropLast(1) else t
    }
}
