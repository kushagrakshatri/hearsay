package dev.offlineflow.cleanup

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
}
