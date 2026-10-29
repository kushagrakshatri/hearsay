package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNamesTest {
    private val common = setOf("hey", "are", "you", "coming", "to", "the", "meetup", "send", "inbox", "updates", "visit", "example", "buy", "an")
    private val isCommon = { w: String -> w.lowercase() in common }

    @Test fun picksNames() {
        val names = ScreenNames.from(
            listOf("Mugdha Birari", "Hey Mugdha, are you coming to the GitHub meetup?", "Send", "Inbox", "@kushagra",
                "INBOX UPDATES", "visit example.com", "Buy an iPhone"),
            isCommon,
        )
        assertEquals("Mugdha", names.first())
        assertTrue(names.containsAll(listOf("Birari", "GitHub", "iPhone")))
        assertFalse(names.contains("Send"))
        assertFalse(names.contains("Hey"))
        assertFalse(names.contains("UPDATES"))
    }

    @Test fun limits() = assertEquals(2, ScreenNames.from(listOf("Zara Quill Vex Ozzie"), isCommon, limit = 2).size)
}
