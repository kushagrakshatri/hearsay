package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundAlikeTest {
    // About what ScreenNames hands over from a busy chat screen.
    private val screen = listOf(
        "Mugdha", "Birari", "GitHub", "iPhone", "Zara", "Quill", "Vex", "Ozzie", "Priya", "Kushagra",
        "Slack", "Notion", "Figma", "Tuesday", "Hearsay", "Anthropic", "Claude", "Gemini", "Pixel", "Android",
        "Jira", "Confluence", "Sunnyvale", "Cupertino", "Vidushi", "Palak", "Rohan", "Ananya", "Deepak", "Marquis",
    )

    @Test fun usuallyNothing() {
        for (t in listOf(
            "let's meet at six at the cafe and bring the report",
            "can you send me the slides by friday please",
            "I'll be there in ten minutes",
            "ok sounds good see you then",
        )) assertEquals(t, emptyList<String>(), SoundAlike.matches(screen, t))
    }

    @Test fun findsSoundAlikes() {
        assertEquals(listOf("Mugdha"), SoundAlike.matches(screen, "hey mug the are you coming tonight"))
        assertEquals(listOf("Kushagra"), SoundAlike.matches(screen, "tell cash agra the build is ready"))
        assertEquals(listOf("GitHub"), SoundAlike.matches(screen, "I pushed it to github last night"))
        assertEquals(listOf("Figma", "Priya"), SoundAlike.matches(screen, "we should ask pria about the figma file"))
    }

    @Test fun skipsNamesAlreadySpelledRight() =
        assertEquals(emptyList<String>(), SoundAlike.matches(screen, "Priya said the meeting moved"))

    @Test fun capsTheList() {
        val many = (1..20).map { "Zed$it" } + listOf("Kushagra")
        assertTrue(SoundAlike.matches(screen + many, "kushagr mugdah github figma pria iphone", max = 5).size <= 5)
    }

    @Test fun cloudMessagesCarryOnlyMatches() {
        val none = LlmCleaner.cleanupMessages(Style.POLISHED, emptyList(), "let's meet at six at the cafe", screen)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user", "assistant", "user", "assistant", "user"), none.map { it.first })
        val some = LlmCleaner.cleanupMessages(Style.POLISHED, emptyList(), "tell cash agra the build is ready", screen)
        val note = some[some.size - 2]
        assertEquals("system", note.first)
        assertTrue(note.second.contains("Kushagra"))
        assertTrue(!note.second.contains("Mugdha"))
    }

    @Test fun noNoteForNoNames() = assertNull(LlmCleaner.namesNote(emptyList()))

    @Test fun soundKey() = assertEquals(SoundAlike.key("Kushagra"), SoundAlike.key("cash agra"))
}
