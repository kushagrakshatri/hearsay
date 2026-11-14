package dev.offlineflow.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PauseCutterTest {
    private val sr = PauseCutter.SR

    /** A 200 Hz tone standing in for speech. */
    private fun tone(sec: Double, amp: Double) = FloatArray((sec * sr).toInt()) { (amp * sin(2 * PI * 200 * it / sr)).toFloat() }
    private fun silence(sec: Double) = FloatArray((sec * sr).toInt())
    private fun join(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var i = 0
        for (p in parts) { p.copyInto(out, i); i += p.size }
        return out
    }

    /** Feeds [x] in 100 ms mic reads; returns (samples seen, cut) at the first cut, or null. */
    private fun firstCut(x: FloatArray, unhandedStart: Long = 0): Pair<Long, Pair<Long, PauseCutter.Kind>>? {
        val c = PauseCutter()
        var i = 0
        while (i < x.size) {
            val n = minOf(1600, x.size - i)
            c.add(x.copyOfRange(i, i + n)); i += n
            c.cutAt(unhandedStart)?.let { return c.seen to it }
        }
        return null
    }

    @Test fun cleanPauseCutsInsideThePause() {
        val x = join(tone(5.0, 0.3), silence(0.4), tone(1.0, 0.3))
        val (_, cut) = firstCut(x)!!
        assertEquals(PauseCutter.Kind.VOLUME, cut.second)
        assertTrue("cut ${cut.first} not in the pause", cut.first in 80_000L until 86_400L)
    }

    @Test fun noisyFloorPauseStillCuts() {
        val rnd = java.util.Random(1)
        val x = join(silence(1.0), tone(5.0, 0.3), silence(0.4), tone(1.0, 0.3))
        for (i in x.indices) x[i] += (rnd.nextGaussian() * 0.01).toFloat()
        val (_, cut) = firstCut(x, unhandedStart = 16_000)!!
        assertEquals(PauseCutter.Kind.VOLUME, cut.second)
        assertTrue("cut ${cut.first} not in the pause", cut.first in 96_000L until 102_400L)
    }

    @Test fun forcedSplitAtEightSecondsAtTheQuietestSpot() {
        // No pause near the floor, just a slightly quieter 150 ms at 6 s.
        val x = join(tone(6.0, 0.3), tone(0.15, 0.1), tone(3.0, 0.3))
        val (seen, cut) = firstCut(x)!!
        assertEquals(PauseCutter.Kind.FORCED, cut.second)
        assertEquals(8L * sr, seen)
        assertTrue("cut ${cut.first} not in the quiet spot", cut.first in 96_000L until 98_400L)
    }

    @Test fun noCutUnderFourSeconds() {
        // A long clean pause, but only 3.5 s since the unhanded speech began.
        assertNull(firstCut(join(tone(2.0, 0.3), silence(1.5))))
    }

    @Test fun piecesAndTailSplitTheSpeechExactly() {
        val audio = FloatArray(200_000) { it.toFloat() }
        val ranges = listOf(10_000L until 40_000L, 45_000L until 90_000L, 120_000L until 150_000L)
        val whole = PauseCutter.slice(audio, ranges)
        for (cuts in listOf(listOf(60_000L), listOf(30_000L, 70_000L), listOf(42_000L, 100_000L, 140_000L))) {
            val bounds = listOf(0L) + cuts + audio.size.toLong()
            val pieces = bounds.zipWithNext { a, b -> PauseCutter.slice(audio, ranges, a, b) }
            assertArrayEquals(whole, join(*pieces.toTypedArray()), 0f)
            // The tail starts exactly at the last cut when the cut falls inside speech.
            val tail = pieces.last()
            if (ranges.any { cuts.last() in it }) assertEquals(cuts.last().toFloat(), tail.first(), 0f)
        }
    }

    @Test fun sliceMatchesTheVadCutWhenUnclipped() {
        val audio = FloatArray(50_000) { it.toFloat() }
        // Padded by 250 ms and merged, clamped to the recording.
        val out = PauseCutter.slice(audio, listOf(2_000L until 10_000L, 12_000L until 20_000L, 40_000L until 49_000L))
        assertEquals(0f, out.first(), 0f)
        assertEquals((24_000 + 50_000 - 36_000), out.size)
    }

    @Test fun describesCutKinds() {
        assertEquals("3 pause, 2 volume, 1 forced", PauseCutter.describe(intArrayOf(3, 2, 1)))
        assertEquals("2 pause, 1 forced", PauseCutter.describe(intArrayOf(2, 0, 1)))
        assertEquals("", PauseCutter.describe(IntArray(0)))
    }

    @Test fun holdToTalkKeepsChunkingAfterALongPause() {
        assertFalse(Recorder.stopsChunking(endFired = true, endsOnPause = false))
        assertTrue(Recorder.stopsChunking(endFired = true, endsOnPause = true))
        assertFalse(Recorder.stopsChunking(endFired = false, endsOnPause = true))
    }
}
