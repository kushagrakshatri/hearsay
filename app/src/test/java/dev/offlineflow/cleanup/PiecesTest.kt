package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class PiecesTest {
    @Test fun separateSentencesStaySeparate() = assertEquals(
        listOf("Hey, are we still on for Friday?", "I booked the table at Rooh."),
        Pieces.units(listOf("Hey, are we still on for Friday?", " I booked the table at Rooh. ")),
    )

    @Test fun correctionsAndListsJoinThePieceBefore() = assertEquals(
        listOf("Let's meet Thursday. No wait, make it Friday.", "My priorities are first the migration. Second, the review. Finally, the runbook."),
        Pieces.units(listOf("Let's meet Thursday.", "No wait, make it Friday.", "My priorities are first the migration.", "Second, the review.", "Finally, the runbook.")),
    )

    @Test fun nobodyIsNotNo() = assertEquals(2, Pieces.units(listOf("I'll be late.", "Nobody else is coming.")).size)

    @Test fun joinAddsMissingPeriods() =
        assertEquals("Sounds good. On my way", Pieces.join(listOf("Sounds good", "On my way")))
}
