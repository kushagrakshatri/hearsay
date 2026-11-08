package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class ListsTest {
    @Test fun ordinalsBecomeNumberedLines() = assertEquals(
        "My priorities this week are:\n1. Finish the API migration\n2. Review Priya's PR\n3. Write the on-call runbook",
        Lists.format("My priorities this week are: first, finish the API migration; second, review Priya's PR; and third, write the on-call runbook."),
    )

    @Test fun finallyEndsTheListAndTheNextSentenceStays() = assertEquals(
        "1. Book the room\n2. Send invites\n3. Order food\n\nThanks!",
        Lists.format("Firstly, book the room. Secondly, send invites. Finally, order food. Thanks!"),
    )

    @Test fun ordinaryUsesStaySentences() {
        for (s in listOf(
            "We'll deliver the first draft by the 12th, then revisions.",
            "First of all, thanks. Second, nothing.",
            "At first I thought so, second time not.",
            "For groceries, we need eggs, milk, spinach, and coffee.",
        )) assertEquals(s, Lists.format(s))
    }
}
