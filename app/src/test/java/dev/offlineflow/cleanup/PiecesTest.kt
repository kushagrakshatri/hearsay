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

    @Test fun joinMendsSeamsInsideTheSentence() = assertEquals(
        "The line has no done while you talked for speech or cleanup, so it fell back. I can't tell yet.",
        Pieces.join(listOf("The line has no done while you talked.", "For speech or cleanup, so it fell back.", "I can't tell yet.")),
    )

    @Test fun unitsMendSeamsTheSameWayAsTheTranscript() {
        val pieces = listOf("I booked the table with.", "Because Rooh was full.", "And then Sarah called.")
        // Live speech hands cleanup Joins.join(pieces); the units it cleans must join back to that text.
        assertEquals(Joins.join(pieces), Joins.join(Pieces.units(pieces)))
        assertEquals(listOf("I booked the table with because Rooh was full. And then Sarah called."), Pieces.units(pieces))
    }
}
