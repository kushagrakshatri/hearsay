package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetsTest {
    private val s = Snippets.parse(
        """
        my email = me@example.com
        my address = 640 Epic Way\nSan Jose, CA
        # comment
        no equals sign here
        zoom link = https://zoom.us/j/123?pwd=abc
        """.trimIndent()
    )

    @Test fun parses() = assertEquals(3, s.entries.size)
    @Test fun matchesWithAsrPunctuation() = assertEquals("me@example.com", s.match("My email."))
    @Test fun multiline() = assertEquals("640 Epic Way\nSan Jose, CA", s.match("my address"))
    @Test fun keepsEqualsInText() = assertEquals("https://zoom.us/j/123?pwd=abc", s.match("Zoom link!"))
    @Test fun leadingInsert() = assertEquals("me@example.com", s.match("Insert my email."))
    @Test fun onlyWholeUtterance() = assertNull(s.match("Send it to my email."))
    @Test fun empty() = assertNull(Snippets.parse("").match("my email"))
}
