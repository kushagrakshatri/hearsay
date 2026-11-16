package dev.offlineflow.telemetry

import android.content.Context
import dev.offlineflow.BuildConfig
import dev.offlineflow.cleanup.Style
import dev.offlineflow.core.Timing
import dev.offlineflow.model.ModelManager

/** Turns a finished dictation into [DictationFacts] and queues its event. Cheap; returns at once. */
object Dictations {
    /** Returns the dictation's id (for the undo / edit follow-ups), or null when diagnostics are off. */
    fun report(
        c: Context, surface: String, t: Timing, totalMs: Long, models: ModelManager, style: Style, app: String?,
        raw: String, final: String, cloudSpeech: Boolean, line: String,
    ): String? {
        if (!Telemetry.available || !dev.offlineflow.Prefs.sendTestLogs(c)) return null
        return runCatching {
            val id = Telemetry.newId()
            Telemetry.dictation(DictationFacts(
                id = id, surface = surface,
                appVersion = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE,
                device = Telemetry.device(), soc = Telemetry.soc(), gpu = Telemetry.gpu(c),
                speechModel = models.selected.name, speechRuntime = t.speechEngine, cloudSpeech = cloudSpeech,
                cleanupModel = t.cleanModel ?: models.selectedCleanup?.shortName, cleanupBackend = t.cleanBackend,
                cloudCleanup = t.cloud, style = style.name, app = app,
                totalMs = totalMs, audioMs = t.audioMs, asrWaitMs = t.asrWait, asrLoadMs = t.asrLoad, asrMs = t.asr,
                liveAudioMs = t.liveAudioMs, liveWaitMs = t.liveWait,
                livePauses = t.livePauses, liveRedone = t.liveRedone, liveFailed = t.liveFailed, liveCuts = t.liveCuts.toList(),
                llmWaitMs = t.llmWait, llmLoadMs = t.llmLoad, promptSetupMs = t.prompt, cleanupMs = t.llm,
                cleanPieces = t.cleanPieces, cleanReused = t.cleanReused, ranLlm = t.ranLlm,
                run = t.cleanRun, pieceRun = t.cleanPieceRun,
                dictationLine = line, cleanupLine = t.cleanLine,
                raw = raw, final = final,
            ))
            id
        }.getOrNull()
    }
}
