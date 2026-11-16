package dev.offlineflow.telemetry

import dev.offlineflow.llm.FullPromptWhy
import dev.offlineflow.llm.RunStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationReportTest {
    private val piece = RunStats(
        engine = "LiteRT-LM graphics chip", speedup = "MTP on", ms = 900, tokens = 24, tokensPerSec = 31.25,
        promptTokens = 40, promptMs = 967, reusedPrompt = true, copyMs = 12, promptPasses = 1, passMs = 240,
        decodeMs = 700, aroundMs = 80, firstReadTokens = 403, firstReadMs = 1000,
    )

    private fun facts(
        runtime: String? = "own engine", model: String = "PARAKEET_V2", cloudSpeech: Boolean = false,
        style: String = "POLISHED", ranLlm: Boolean = true, cloudCleanup: Boolean = false, livePauses: Int = 2,
        run: RunStats? = null, pieceRun: RunStats? = piece, raw: String = "um so hear say works", final: String = "So Hearsay works.",
    ) = DictationFacts(
        id = "abc12345", surface = "keyboard", appVersion = "0.1.0-build.86", build = 86,
        device = "Google Pixel 9", soc = "Google Tensor G4", gpu = "Mali-G715",
        speechModel = model, speechRuntime = runtime, cloudSpeech = cloudSpeech,
        cleanupModel = "Gemma 4 E2B", cleanupBackend = "LiteRT-LM GPU", cloudCleanup = cloudCleanup,
        style = style, app = "com.whatsapp",
        totalMs = 2400, audioMs = 4200, asrWaitMs = 0, asrLoadMs = 0, asrMs = 300, liveAudioMs = 3000, liveWaitMs = 100,
        livePauses = livePauses, liveRedone = 0, liveFailed = false, liveCuts = listOf(1, 0, 1),
        llmWaitMs = 0, llmLoadMs = 0, promptSetupMs = 0, cleanupMs = 1900, cleanPieces = 3, cleanReused = 2, ranLlm = ranLlm,
        run = run, pieceRun = pieceRun, dictationLine = "2.4 s total", cleanupLine = "Gemma 4 E2B · LiteRT-LM GPU",
        raw = raw, final = final,
    )

    @Test fun tagsNameTheEnginesAndTheApp() {
        val t = DictationReport.fields(facts()).tags
        assertEquals("own", t["asr_engine"])
        assertEquals("none", t["asr_fallback"])
        assertEquals("LiteRT-LM GPU", t["cleanup_backend"])
        assertEquals("Gemma 4 E2B", t["cleanup_model"])
        assertEquals("used", t["live_speech"])
        assertEquals("build-86", t["build"])
        assertEquals("com.whatsapp", t["app"])
        assertEquals("polished", t["style"])
        assertEquals("abc12345", t["dictation_id"])
    }

    @Test fun sherpaFallbackKeepsItsReason() {
        val (e, why) = DictationReport.asrEngine(facts(runtime = "sherpa (own engine failed: bad tensor)"))
        assertEquals("sherpa", e)
        assertEquals("own engine failed: bad tensor", why)
        assertEquals("whisper" to null, DictationReport.asrEngine(facts(runtime = null, model = "WHISPER_BASE_EN")))
        assertEquals("cloud" to null, DictationReport.asrEngine(facts(cloudSpeech = true)))
    }

    @Test fun cleanupBackendWhenNoModelRan() {
        assertEquals("rules", DictationReport.cleanupBackend(facts(ranLlm = false)))
        assertEquals("verbatim", DictationReport.cleanupBackend(facts(style = "VERBATIM")))
        assertEquals("chatgpt", DictationReport.cleanupBackend(facts(cloudCleanup = true)))
        assertEquals("none", DictationReport.fields(facts(ranLlm = false)).tags["cleanup_model"])
        assertEquals("off", DictationReport.liveSpeech(facts(livePauses = -1)))
    }

    @Test fun dictationContextCarriesEveryNumber() {
        val d = DictationReport.fields(facts()).contexts.getValue("dictation")
        assertEquals(2400L, d["total_ms"])
        assertEquals(4200L, d["audio_ms"])
        assertEquals(300L, d["speech_ms"])
        assertEquals(2, d["live_pieces"])
        assertEquals(1, d["live_cuts_forced"])
        assertEquals(1900L, d["cleanup_ms"])
        assertEquals(2, d["clean_pieces_done_live"])
        assertEquals(100L, d["other_ms"]) // 2400 - (100 + 300 + 1900)
    }

    @Test fun pieceRunSplitsPromptReadIntoSetupAndPasses() {
        val c = DictationReport.fields(facts()).contexts.getValue("cleanup_last_piece")
        assertEquals(727L, c["prompt_setup_ms"])
        assertEquals(240L, c["prompt_passes_ms"])
        assertEquals(31.3, c["tokens_per_sec"])
        assertEquals(true, c["mtp"])
        assertEquals(80L, c["outside_ms"])
        assertFalse(c.containsKey("spec_accept_pct"))
        assertFalse(DictationReport.fields(facts()).contexts.containsKey("cleanup"))
    }

    @Test fun wholeTextRunAndAcceptance() {
        val run = RunStats(engine = "llama.cpp processor", speedup = "Faster cleanup on, 78% guesses accepted", ms = 640, tokens = 21,
            tokensPerSec = 21.0, reusedPrompt = false, fullPromptWhy = FullPromptWhy.NAMES_NOTE)
        val c = DictationReport.fields(facts(run = run, pieceRun = null)).contexts.getValue("cleanup")
        assertEquals(78, c["spec_accept_pct"])
        assertEquals("names note", c["full_prompt_why"])
        assertEquals("Gemma 4 E2B · LiteRT-LM GPU", c["line"])
        assertNull(c["prompt_setup_ms"])
        assertNull(DictationReport.acceptedPct("Faster cleanup off"))
    }

    @Test fun extrasHoldBothTextsCappedAndTagsCapped() {
        val x = DictationReport.fields(facts()).extras
        assertEquals("um so hear say works", x["raw_transcript"])
        assertEquals("So Hearsay works.", x["final_text"])
        val long = "a".repeat(DictationReport.TEXT_MAX + 50)
        val f = DictationReport.fields(facts(raw = long, runtime = "sherpa (" + "x".repeat(300) + ")"))
        assertEquals(DictationReport.TEXT_MAX, (f.extras["raw_transcript"] as String).length)
        assertEquals(DictationReport.TEXT_MAX + 50, f.contexts.getValue("dictation")["raw_chars"])
        assertTrue(f.tags.values.all { it.length <= 200 })
    }
}
