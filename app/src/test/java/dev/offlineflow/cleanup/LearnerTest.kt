package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerTest {
    private val common = setOf(
        "ask", "about", "it", "call", "now", "push", "to", "today", "i", "think", "their", "there", "here",
        "will", "meet", "you", "at", "the", "cafe", "six", "let's", "do", "restaurant", "seven", "instead",
        "also", "bring", "mark", "said", "hi", "send", "this",
    )
    private val isCommon = { w: String -> w.lowercase() in common }
    private fun learn(a: String, b: String) = Learner.corrections(a, b, isCommon)

    @Test fun learnsFinishedName() =
        assertEquals(listOf(Learner.Learned("Kushagr", "Kushagra")), learn("Ask Kushagr about it.", "Ask Kushagra about it."))

    @Test fun learnsSplitMishearing() {
        val l = learn("Call cash agra now.", "Call Kushagra now.")
        assertEquals(listOf(Learner.Learned("cash agra", "Kushagra")), l)
        assertEquals("cash agra -> Kushagra", l[0].line(isCommon))
    }

    @Test fun learnsCaseOnlySpelling() {
        val l = learn("push it to github today", "Push it to GitHub today")
        assertEquals(listOf(Learner.Learned("github", "GitHub")), l)
        assertEquals("GitHub", l[0].line(isCommon))
    }

    @Test fun ignoresEverydayWords() = assertTrue(learn("I think their here", "I think there here").isEmpty())

    @Test fun ignoresRewrites() =
        assertTrue(learn("I will meet you at the cafe at six", "Let's do the restaurant at seven instead").isEmpty())

    @Test fun ignoresWordsTypedAfter() =
        assertEquals(listOf(Learner.Learned("Kushagr", "Kushagra")), learn("Ask Kushagr about it.", "Ask Kushagra about it. Also bring Zed"))

    @Test fun ignoresUnrelatedWord() = assertTrue(learn("Hi Mark, send this", "Hi Priyanka, send this").isEmpty())

    @Test fun commonHeardBecomesTerm() {
        val l = learn("Mark said hi", "Marc said hi")
        assertEquals(listOf(Learner.Learned("Mark", "Marc")), l)
        assertEquals("Marc", l[0].line(isCommon))
    }

    @Test fun addsToDictionaryOnce() {
        val learned = listOf(Learner.Learned("Kushagr", "Kushagra"))
        val (text, added) = Learner.addTo("GitHub", learned, isCommon)!!
        assertEquals("GitHub\nKushagr -> Kushagra", text)
        assertEquals(learned, added)
        assertNull(Learner.addTo(text, learned, isCommon))
    }

    @Test fun caseTermReplacesOldSpelling() {
        val (text, _) = Learner.addTo("Github\nHearsay", listOf(Learner.Learned("github", "GitHub")), isCommon)!!
        assertEquals("Hearsay\nGitHub", text)
    }
}
