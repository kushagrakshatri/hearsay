package dev.offlineflow.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefillPassesTest {
    @Test fun shortTurnIsOnePass() {
        assertEquals(1, PrefillPasses.count(1))
        assertEquals(1, PrefillPasses.count(31))
        assertEquals(1, PrefillPasses.count(128))
    }

    @Test fun oneTokenOverIsTwoPasses() = assertEquals(2, PrefillPasses.count(129))

    @Test fun cleanupPromptIsFourPasses() {
        // System prompt + 4 few-shots: 403 tokens = 3 full runs of 128 and one for the last 19.
        assertEquals(4, PrefillPasses.count(403))
        assertEquals(4, PrefillPasses.count(403, greedy = true))
    }

    @Test fun gpuCoversABigRemainderWithTheBigGraph() {
        // LiteRT-LM's own examples: 640 with {512, 128, 32} -> 512 + 128; 768 -> 512 + 512.
        assertEquals(2, PrefillPasses.count(640, listOf(512, 128, 32)))
        assertEquals(2, PrefillPasses.count(768, listOf(512, 128, 32)))
        assertEquals(1, PrefillPasses.count(640)) // 640 >= 1024 / 2: one run of the 1024 graph
        assertEquals(5, PrefillPasses.count(640, greedy = true))
    }

    @Test fun nothingToRead() = assertEquals(0, PrefillPasses.count(0))
}
