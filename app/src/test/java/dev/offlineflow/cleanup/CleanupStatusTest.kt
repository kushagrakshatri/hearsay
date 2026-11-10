package dev.offlineflow.cleanup

import dev.offlineflow.llm.FullPromptWhy
import dev.offlineflow.llm.RunStats
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupStatusTest {
    @Test fun llamaLine() {
        val s = RunStats("llama.cpp processor", "Faster cleanup on, 78% guesses accepted", 640, 14, 21.4)
        assertEquals(
            "Qwen3.5 2B \u00b7 llama.cpp processor \u00b7 Faster cleanup on, 78% guesses accepted \u00b7 640 ms \u00b7 21 tokens/s",
            CleanupStatus.line("Qwen3.5 2B", s),
        )
    }

    @Test fun estimatedSpeedIsMarked() {
        val s = RunStats("LiteRT-LM graphics chip", "MTP off (PowerVR GPU)", 900, 20, 22.2, approx = true)
        assertEquals(
            "Gemma 4 E2B \u00b7 LiteRT-LM graphics chip \u00b7 MTP off (PowerVR GPU) \u00b7 900 ms \u00b7 ~22 tokens/s",
            CleanupStatus.line("Gemma 4 E2B", s),
        )
    }

    @Test fun noSpeedWhenUnmeasured() {
        val s = RunStats("llama.cpp processor", "Faster cleanup off", 300, 0, null)
        assertEquals("llama.cpp processor \u00b7 Faster cleanup off \u00b7 300 ms", CleanupStatus.line("", s))
    }

    private val gpu = RunStats("LiteRT-LM graphics chip", "MTP on", 1137, 17, 24.0)

    @Test fun livePieceReusedPrompt() {
        val s = gpu.copy(promptTokens = 28, promptMs = 436, reusedPrompt = true)
        assertEquals(
            "Gemma 4 E2B \u00b7 LiteRT-LM graphics chip \u00b7 MTP on \u00b7 1137 ms \u00b7 24 tokens/s \u00b7 last piece: reused prompt, 28 tokens read in 436 ms",
            CleanupStatus.line("Gemma 4 E2B", s, piece = true),
        )
    }

    @Test fun reReadWithCopyingOff() {
        val s = gpu.copy(promptTokens = 432, promptMs = 1763, reusedPrompt = false, fullPromptWhy = FullPromptWhy.CLONE_ERROR, copyingOff = true)
        assertEquals("re-read prompt (432 tokens) in 1.8 s, copying off: copy error", CleanupStatus.prompt(s))
    }

    @Test fun reReadOnceAfterAFailure() {
        val s = gpu.copy(reusedPrompt = false, fullPromptWhy = FullPromptWhy.OPEN_FAILED)
        assertEquals("re-read prompt, copying skipped: open failed", CleanupStatus.prompt(s))
    }

    @Test fun namesNote() {
        val s = gpu.copy(promptTokens = 470, reusedPrompt = false, fullPromptWhy = FullPromptWhy.NAMES_NOTE)
        assertEquals("re-read prompt (470 tokens), names note", CleanupStatus.prompt(s))
    }

    @Test fun retriedAfterCopyError() {
        val s = gpu.copy(promptTokens = 432, promptMs = 900, reusedPrompt = false, fullPromptWhy = FullPromptWhy.CLONE_ERROR, retried = true)
        assertEquals("re-read prompt (432 tokens) in 900 ms, redone after a copy error", CleanupStatus.prompt(s))
    }

    @Test fun llamaSaysNothingAboutThePrompt() {
        assertEquals(null, CleanupStatus.prompt(RunStats("llama.cpp processor", "Faster cleanup off", 300, 0, null)))
    }
}
