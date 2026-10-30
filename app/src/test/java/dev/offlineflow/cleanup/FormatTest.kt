package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private val ex = listOf("u1", "a1")

    @Test fun lfmIsPlainChatMlWithoutThinkOrManualBos() {
        val f = LlmCleaner.Format.CHATML
        val p = f.prefix("sys", ex)
        assertTrue(p.startsWith("<|im_start|>system\nsys<|im_end|>\n")) // BOS comes from the tokenizer
        assertTrue(p.contains("<|im_start|>assistant\na1<|im_end|>\n"))
        assertEquals("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n", f.turn("hi"))
        assertFalse(f.turn("hi").contains("think"))
    }

    @Test fun miniCpmStartsWithBosAndTurnsThinkingOff() {
        val f = LlmCleaner.Format.MINICPM_NO_THINK
        assertTrue(f.prefix("sys", ex).startsWith("<s><|im_start|>system\nsys<|im_end|>\n"))
        assertTrue(f.turn("hi").endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun stripStopsAtImEnd() {
        assertEquals("Hello there", LlmCleaner.strip("Hello there<|im_end|>\nextra"))
    }
}
