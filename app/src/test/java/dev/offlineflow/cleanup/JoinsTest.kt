package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JoinsTest {
    /** The app's everyday-word list (unit tests run in the app module's directory). */
    private val common: Set<String> = File("src/main/assets/words/common-en.txt")
        .takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toHashSet() ?: emptySet()

    private fun join(vararg parts: String) = Joins.join(parts.toList(), common = common)
    private fun cleaned(vararg parts: String) = Joins.join(parts.toList(), period = true, common = common)

    @Test fun wordListLoaded() = assertTrue("common-en.txt not found", "oranges" in common)

    // The Nov 9 dictation, as Parakeet heard it piece by piece.
    @Test fun realExample() = assertEquals(
        "That run never went live. The line has no done while you talked for speech or cleanup, so it fell back " +
            "to doing all thirty-two point seven seconds. After you let go, I can't tell yet whether",
        join(
            "That run never went live. The line has no done while you talked.",
            "For speech or cleanup, so it fell back to doing all thirty-two point seven seconds.",
            "After you let go, I can't tell yet whether",
        ),
    )

    @Test fun dropsPeriodAfterWordThatCantEndASentence() {
        assertEquals("Then I'll head out with Kashish for dinner.", join("Then I'll head out with.", "Kashish for dinner."))
        assertEquals("I'm more tired than usual this week.", join("I'm more tired than.", "Usual this week."))
        assertEquals("I can stay until I finish the report.", join("I can stay until.", "I finish the report."))
        assertEquals("I finished the draft of the proposal.", join("I finished the draft of.", "The proposal."))
    }

    @Test fun lowercasesAfterAnOpenPiece() {
        assertEquals("We bought apples and oranges for the party.", join("We bought apples and", "Oranges for the party."))
        assertEquals("I think we should take the train instead.", join("I think we should take the", "Train instead."))
        assertEquals("Please remind me to pick up the dry cleaning.", join("Please remind me to", "Pick up the dry cleaning."))
        assertEquals("The new phone has a better camera.", join("The new phone has a", "Better camera."))
    }

    @Test fun nextPieceStartingWithPreposition() {
        assertEquals("The rent went up because of the new pool.", join("The rent went up because", "Of the new pool."))
        assertEquals("It costs more than ours.", join("It costs more.", "Than ours."))
        assertEquals("It broke because the tests timed out.", join("It broke.", "Because the tests timed out."))
    }

    @Test fun dayOrNumberAfterPreposition() {
        assertEquals("We tried the new restaurant on Saturday and it was great.", join("We tried the new restaurant on.", "Saturday and it was great."))
        assertEquals("My flight lands at seven, so I'll be home.", join("My flight lands at", "Seven, so I'll be home."))
    }

    @Test fun lowercaseStartOrObjectPronounContinuesAnOpenPiece() {
        assertEquals("She said the apartment is bigger than ours.", join("She said the apartment is", "bigger than ours."))
        assertEquals("Priya is driving us home tonight.", join("Priya is driving", "Us home tonight."))
    }

    // ---- seams that must stay as they are

    @Test fun realSentenceEndsStay() {
        assertEquals("I went to the store. We bought apples.", join("I went to the store.", "We bought apples."))
        assertEquals("It was getting late. After the show, we took a cab home.", join("It was getting late.", "After the show, we took a cab home."))
        assertEquals("After dinner we watched a movie. Then we went for a walk.", join("After dinner we watched a movie.", "Then we went for a walk."))
        assertEquals("That's annoying. Of course we'll pay it.", join("That's annoying.", "Of course we'll pay it."))
        assertEquals("I'd love to. See you on Friday then.", join("I'd love to.", "See you on Friday then."))
    }

    @Test fun openersAfterARealEndStay() {
        assertEquals("I need a new laptop. For example, the battery dies.", join("I need a new laptop.", "For example, the battery dies."))
        assertEquals("We should leave soon. To be honest, I'm tired.", join("We should leave soon.", "To be honest, I'm tired."))
        assertEquals("The kids loved the museum. For lunch we had pizza.", join("The kids loved the museum.", "For lunch we had pizza."))
        assertEquals("The demo went well. Which part did you like?", join("The demo went well.", "Which part did you like?"))
    }

    @Test fun questionAndExclamationMarksStay() {
        assertEquals("Can you send me the report? Before Friday?", join("Can you send me the report?", "Before Friday?"))
        assertEquals("That was great! For real.", join("That was great!", "For real."))
    }

    @Test fun namesIAndAcronymsKeepTheirCapitals() {
        assertEquals("I sent it to Priya for review.", join("I sent it to", "Priya for review."))
        assertEquals("I'll meet with Sarah tomorrow.", join("I'll meet with.", "Sarah tomorrow."))
        assertEquals("Please wait until I finish.", join("Please wait until.", "I finish."))
        assertEquals("We moved it to NASA's schedule.", join("We moved it to", "NASA's schedule."))
        assertEquals("I uploaded it to GitHub.", join("I uploaded it to", "GitHub."))
    }

    @Test fun unknownWordsAfterAnOpenPieceStayCapitalizedWithoutTheWordList() =
        assertEquals("We bought apples and Oranges.", Joins.join(listOf("We bought apples and", "Oranges."), common = emptySet()))

    @Test fun continuesIsCheap() {
        assertTrue(Joins.continues("the line has no done while you talked.", "For speech or cleanup, so it fell back"))
        assertFalse(Joins.continues("Thanks for the update.", "I'll look into the bug and"))
        assertFalse(Joins.continues("", "For speech"))
        assertFalse(Joins.continues("Here is the plan:", "for now we wait"))
    }

    // ---- cleaned stretches (live cleanup): an open stretch that doesn't carry on still gets a period

    @Test fun cleanedStretchesGetPeriodsOnlyWhereTheSentenceEnds() {
        assertEquals("Sounds good. On my way", cleaned("Sounds good", "On my way"))
        assertEquals("We bought apples and oranges.", cleaned("We bought apples and.", "Oranges."))
        assertEquals(
            "The line has no done while you talked for speech or cleanup. I can't tell yet.",
            cleaned("The line has no done while you talked.", "For speech or cleanup", "I can't tell yet."),
        )
    }
}
