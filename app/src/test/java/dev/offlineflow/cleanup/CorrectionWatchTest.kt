package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectionWatchTest {
    private fun watch() = CorrectionWatch(maxAgeMs = 10_000).apply {
        begin("f", "Hello Kushagr about it.", 6, "Kushagr about it.", 0)
    }

    @Test fun seesFix() =
        assertEquals("Kushagr about it." to "Kushagra about it.", watch().edited("f", "Hello Kushagra about it.", 1000))

    @Test fun unchanged() = assertNull(watch().edited("f", "Hello Kushagr about it.", 1000))
    @Test fun otherField() = assertNull(watch().edited("g", "Hello Kushagra about it.", 1000))
    @Test fun editedAround() = assertNull(watch().edited("f", "Hi Kushagra about it.", 1000))
    @Test fun tooOld() = assertNull(watch().edited("f", "Hello Kushagra about it.", 20_000))

    @Test fun textAfterKept() {
        val w = CorrectionWatch().apply { begin("f", "A Kushagr B", 2, "Kushagr", 0) }
        assertEquals("Kushagr" to "Kushagra", w.edited("f", "A Kushagra B", 1))
    }
}
