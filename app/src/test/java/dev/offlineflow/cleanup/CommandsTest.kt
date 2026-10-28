package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test fun selectionCommands() {
        assertTrue(Commands.forSelection("Make it shorter."))
        assertTrue(Commands.forSelection("Turn this into bullets"))
        assertTrue(Commands.forSelection("Can you make this more formal?"))
        assertTrue(Commands.forSelection("translate to Spanish"))
    }

    @Test fun selectionDictationReplaces() {
        assertFalse(Commands.forSelection("See you at six."))
        assertFalse(Commands.forSelection("Maker faire is on Sunday."))
    }

    @Test fun lastInsertNeedsThisOrThat() {
        assertTrue(Commands.forLastInsert("Make that more formal."))
        assertTrue(Commands.forLastInsert("Okay, turn this into a list"))
        assertTrue(Commands.forLastInsert("clean that up"))
        assertFalse(Commands.forLastInsert("Make sure you bring snacks."))
        assertFalse(Commands.forLastInsert("Make it happen."))
    }

    @Test fun scratch() {
        assertTrue(Commands.isScratch("Scratch that."))
        assertFalse(Commands.isScratch("Scratch that itch."))
    }

    @Test fun instruction() = assertEquals("Make it shorter", Commands.instruction("make it shorter."))
}
