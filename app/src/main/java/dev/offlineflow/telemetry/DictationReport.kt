package dev.offlineflow.telemetry

import dev.offlineflow.llm.RunStats

/**
 * One finished dictation as plain values (no Android types), so the event's fields can be
 * unit-tested. Filled from [dev.offlineflow.core.Timing] and the cleaner when the text goes in.
 */
data class DictationFacts(
    val id: String,
    /** "keyboard" or "floating mic". */
    val surface: String,
    val appVersion: String,
    val build: Int,
    val device: String,
    val soc: String,
    val gpu: String,
    /** The speech model's enum name (PARAKEET_V2, WHISPER_BASE_EN). */
    val speechModel: String,
    /** The engine's own label ("own engine", "sherpa (own engine failed: …)"); null for Whisper. */
    val speechRuntime: String?,
    val cloudSpeech: Boolean,
    /** Cleanup model short name ("Emberon-2"); null when no model was loaded for this dictation. */
    val cleanupModel: String?,
    /** "LiteRT-LM GPU", "LiteRT-LM CPU", "llama.cpp CPU"; null when none. */
    val cleanupBackend: String?,
    val cloudCleanup: Boolean,
    val style: String,
    val app: String?,
    val totalMs: Long,
    val audioMs: Long,
    val asrWaitMs: Long,
    val asrLoadMs: Long,
    val asrMs: Long,
    val liveAudioMs: Long,
    val liveWaitMs: Long,
    /** Pieces handed over at pauses; -1 when live speech was off. */
    val livePauses: Int,
    val liveRedone: Int,
    val liveFailed: Boolean,
    /** Indexed by PauseCutter.Kind: pause, volume dip, forced. */
    val liveCuts: List<Int>,
    val llmWaitMs: Long,
    val llmLoadMs: Long,
    val promptSetupMs: Long,
    val cleanupMs: Long,
    val cleanPieces: Int,
    val cleanReused: Int,
    val ranLlm: Boolean,
    /** The whole-text model call, when one ran (null when every piece was cleaned live). */
    val run: RunStats?,
    /** The newest live piece's model call. */
    val pieceRun: RunStats?,
    /** The "Last dictation" and "Last cleanup" lines exactly as Settings shows them. */
    val dictationLine: String,
    val cleanupLine: String?,
    /** What was heard, before cleanup. */
    val raw: String,
    /** What was typed into the field. */
    val final: String,
)

/** A Sentry event's fields, independent of the SDK. */
data class EventFields(
    val tags: Map<String, String>,
    val contexts: Map<String, Map<String, Any>>,
    val extras: Map<String, Any>,
)

object DictationReport {
    /** Sentry caps tag values at 200 characters. */
    private const val TAG_MAX = 200
    /** Dictated text is kept whole up to this; longer is cut (the counts in the dictation context stay exact). */
    const val TEXT_MAX = 8000

    private val ACCEPTED = Regex("""(\d+)% guesses accepted""")

    /** Speech runtime in one word plus why it isn't the usual one: ("own", null), ("sherpa", "own engine failed: …"). */
    fun asrEngine(f: DictationFacts): Pair<String, String?> {
        if (f.cloudSpeech) return "cloud" to null
        if (f.speechModel.startsWith("WHISPER")) return "whisper" to null
        val r = f.speechRuntime ?: return "unknown" to null
        return when {
            r.startsWith("own engine") -> "own" to (if ("no layer-11" in r) "no layer-11 yet" else null)
            r.startsWith("sherpa") -> "sherpa" to (r.substringAfter("(", "").substringBeforeLast(")").ifEmpty { null })
            else -> r to null
        }
    }

    /** Where cleanup ran: "LiteRT-LM GPU", "llama.cpp CPU", "chatgpt", "verbatim" or "rules". */
    fun cleanupBackend(f: DictationFacts): String = when {
        f.style == "VERBATIM" -> "verbatim"
        f.cloudCleanup -> "chatgpt"
        f.ranLlm && f.cleanupBackend != null -> f.cleanupBackend
        else -> "rules"
    }

    fun liveSpeech(f: DictationFacts): String = when {
        f.livePauses < 0 -> "off"
        f.liveFailed -> "failed"
        f.livePauses == 0 -> "no pause"
        else -> "used"
    }

    /** "78% guesses accepted" → 78; null when the engine didn't report a rate. */
    fun acceptedPct(speedup: String): Int? = ACCEPTED.find(speedup)?.groupValues?.get(1)?.toIntOrNull()

    fun fields(f: DictationFacts): EventFields {
        val (engine, fallback) = asrEngine(f)
        val tags = linkedMapOf(
            "dictation_id" to f.id,
            "surface" to f.surface,
            "app_version" to f.appVersion,
            "build" to "build-${f.build}",
            "device" to f.device,
            "soc" to f.soc,
            "gpu" to f.gpu.ifBlank { "unknown" },
            "asr_model" to if (f.cloudSpeech) "cloud" else f.speechModel,
            "asr_engine" to engine,
            "asr_fallback" to (fallback ?: "none"),
            "cleanup_model" to (if (f.cloudCleanup) "chatgpt" else f.cleanupModel?.takeIf { f.ranLlm } ?: "none"),
            "cleanup_backend" to cleanupBackend(f),
            "style" to f.style.lowercase(),
            "app" to (f.app ?: "unknown"),
            "live_speech" to liveSpeech(f),
        ).mapValues { it.value.take(TAG_MAX) }

        val other = f.totalMs - (f.liveWaitMs + f.asrWaitMs + f.asrLoadMs + f.asrMs + f.llmWaitMs + f.llmLoadMs + f.promptSetupMs + f.cleanupMs)
        val dictation = linkedMapOf<String, Any>(
            "total_ms" to f.totalMs,
            "audio_ms" to f.audioMs,
            "speech_ms" to f.asrMs,
            "speech_wait_ms" to f.asrWaitMs,
            "speech_load_ms" to f.asrLoadMs,
            "live_audio_ms" to f.liveAudioMs,
            "live_wait_ms" to f.liveWaitMs,
            "live_pieces" to f.livePauses,
            "live_redone" to f.liveRedone,
            "live_failed" to f.liveFailed,
            "live_cuts_pause" to f.liveCuts.getOrElse(0) { 0 },
            "live_cuts_volume" to f.liveCuts.getOrElse(1) { 0 },
            "live_cuts_forced" to f.liveCuts.getOrElse(2) { 0 },
            "cleanup_wait_ms" to f.llmWaitMs,
            "cleanup_load_ms" to f.llmLoadMs,
            "first_use_prompt_ms" to f.promptSetupMs,
            "cleanup_ms" to f.cleanupMs,
            "clean_pieces" to f.cleanPieces,
            "clean_pieces_done_live" to f.cleanReused,
            "other_ms" to other,
            "raw_chars" to f.raw.length,
            "final_chars" to f.final.length,
            "line" to f.dictationLine,
        )
        val contexts = linkedMapOf<String, Map<String, Any>>("dictation" to dictation)
        f.run?.let { contexts["cleanup"] = run(it) + listOfNotNull(f.cleanupLine?.let { l -> "line" to l }) }
        f.pieceRun?.takeIf { it !== f.run }?.let { contexts["cleanup_last_piece"] = run(it) }

        val extras = linkedMapOf<String, Any>(
            "raw_transcript" to f.raw.take(TEXT_MAX),
            "final_text" to f.final.take(TEXT_MAX),
        )
        return EventFields(tags, contexts, extras)
    }

    /** Every number of one model call ([RunStats]), with the prompt read split into setup and passes. */
    fun run(s: RunStats): Map<String, Any> {
        val m = linkedMapOf<String, Any?>(
            "engine" to s.engine,
            "speedup" to s.speedup,
            "spec_accept_pct" to acceptedPct(s.speedup),
            "mtp" to when {
                s.speedup.startsWith("MTP on") -> true
                s.speedup.startsWith("MTP off") -> false
                else -> null
            },
            "ms" to s.ms,
            "tokens" to s.tokens,
            "tokens_per_sec" to s.tokensPerSec?.let { Math.round(it * 10) / 10.0 },
            "approx" to s.approx,
            "prompt_tokens" to s.promptTokens,
            "prompt_read_ms" to s.promptMs,
            "prompt_passes" to s.promptPasses,
            "pass_ms" to s.passMs,
            "prompt_setup_ms" to setupMs(s),
            "prompt_passes_ms" to s.passMs?.let { each -> s.promptPasses?.let { it * each } },
            "reused_prompt" to s.reusedPrompt,
            "full_prompt_why" to s.fullPromptWhy?.words,
            "copying_off" to s.copyingOff,
            "retried" to s.retried,
            "copy_ms" to s.copyMs,
            "decode_ms" to s.decodeMs,
            "outside_ms" to s.aroundMs,
            "first_read_tokens" to s.firstReadTokens,
            "first_read_ms" to s.firstReadMs,
        )
        @Suppress("UNCHECKED_CAST")
        return m.filterValues { it != null } as Map<String, Any>
    }

    /** The part of the prompt read outside the model's fixed passes (as in "Last cleanup"); null when unknown. */
    fun setupMs(s: RunStats): Long? {
        val passes = s.promptPasses?.takeIf { it > 0 } ?: return null
        val read = s.promptMs ?: return null
        val each = s.passMs ?: return null
        return (read - passes * each).coerceAtLeast(0)
    }
}
