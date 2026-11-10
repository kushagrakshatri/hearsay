package dev.offlineflow.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonePolicyTest {
    @Test fun oneFailureKeepsCopyingOn() {
        val p = ClonePolicy()
        assertFalse(p.failed(FullPromptWhy.CLONE_ERROR))
        assertTrue(p.on)
        assertEquals(FullPromptWhy.CLONE_ERROR, p.lastFailure)
    }

    @Test fun threeInARowTurnsItOffForGood() {
        val p = ClonePolicy()
        assertFalse(p.failed(FullPromptWhy.CLONE_ERROR))
        assertFalse(p.failed(FullPromptWhy.OPEN_FAILED))
        assertTrue(p.failed(FullPromptWhy.CLONE_ERROR))
        assertFalse(p.on)
        p.succeeded()
        assertFalse("a later success doesn't turn it back on", p.on)
        assertFalse("reported off only once", p.failed(FullPromptWhy.CLONE_ERROR))
    }

    @Test fun successResetsTheCount() {
        val p = ClonePolicy()
        p.failed(FullPromptWhy.CLONE_ERROR); p.failed(FullPromptWhy.CLONE_ERROR)
        p.succeeded()
        assertEquals(0, p.failuresInARow)
        assertFalse(p.failed(FullPromptWhy.CLONE_ERROR))
        assertFalse(p.failed(FullPromptWhy.CLONE_ERROR))
        assertTrue(p.on)
    }

    @Test fun customLimit() {
        val p = ClonePolicy(maxFailures = 1)
        assertTrue(p.failed(FullPromptWhy.NO_BASE))
        assertFalse(p.on)
    }
}
