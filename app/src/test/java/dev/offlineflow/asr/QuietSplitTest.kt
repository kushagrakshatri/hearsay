package dev.offlineflow.asr

import org.junit.Assert.assertTrue
import org.junit.Test

class QuietSplitTest {
    @Test fun cutsInTheQuietStretch() {
        val x = FloatArray(160_000) { 0.3f }
        for (i in 80_000 until 84_000) x[i] = 0f
        val p = QuietSplit.point(x)
        assertTrue("cut at $p", p in 80_000..84_000)
    }

    @Test fun staysInsideForShortInput() {
        val x = FloatArray(1000) { 0.1f }
        assertTrue(QuietSplit.point(x) in 0..x.size)
    }
}
