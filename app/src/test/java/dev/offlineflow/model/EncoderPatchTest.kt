package dev.offlineflow.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class EncoderPatchTest {
    private val src = ByteArray(3_000_000) { (it * 31 + 7).toByte() }

    @Test fun replacesAndInserts() {
        val edits = listOf(
            EncoderPatch.Edit(2, src.copyOfRange(2, 4), byteArrayOf(9, 9)),
            EncoderPatch.Edit(2_500_000, ByteArray(0), byteArrayOf(1, 2, 3)),
        )
        val out = ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        EncoderPatch.patch(src.inputStream(), out, edits, md)
        val want = src.copyOfRange(0, 2) + byteArrayOf(9, 9) + src.copyOfRange(4, 2_500_000) + byteArrayOf(1, 2, 3) +
            src.copyOfRange(2_500_000, src.size)
        assertArrayEquals(want, out.toByteArray())
        // The input digest covers exactly the original bytes.
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(src), md.digest())
    }

    @Test fun refusesUnexpectedBytes() {
        val edits = listOf(EncoderPatch.Edit(10, byteArrayOf(0x55, 0x55), byteArrayOf(1)))
        val r = runCatching { EncoderPatch.patch(src.inputStream(), ByteArrayOutputStream(), edits) }
        assertTrue(r.isFailure)
    }

    @Test fun pinnedPatchAddsExactly73Bytes() {
        val added = EncoderPatch.EDITS.sumOf { it.new.size - it.old.size }
        assertEquals(EncoderPatch.PATCHED_BYTES - EncoderPatch.ORIGINAL_BYTES, added.toLong())
        assertEquals(73, added)
    }
}
