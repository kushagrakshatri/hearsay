package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTest {
    private val d = Dictionary.parse(
        """
        Hearsay
        GitHub
        Kushagra
        F5
        sherpa onnx -> sherpa-onnx
        # comment
        """.trimIndent()
    )

    @Test fun parses() {
        assertEquals(listOf("Hearsay", "GitHub", "Kushagra", "F5"), d.terms)
        assertEquals(listOf("sherpa onnx" to "sherpa-onnx"), d.fixes)
    }

    @Test fun fixesCase() = assertEquals("Push it to GitHub today.", d.apply("Push it to github today."))
    @Test fun joinsSplitWords() = assertEquals("I'm building Hearsay now.", d.apply("I'm building hear say now."))
    @Test fun oneLetterOff() = assertEquals("Ask Kushagra.", d.apply("Ask Kushagr."))
    @Test fun appliesFix() = assertEquals("It runs on sherpa-onnx.", d.apply("It runs on Sherpa ONNX."))
    @Test fun shortTerm() = assertEquals("I work at F5.", d.apply("I work at f5."))
    @Test fun leavesOtherWordsAlone() = assertEquals("Hear me say it, then hearing.", d.apply("Hear me say it, then hearing."))
    @Test fun doesNotJoinAcrossPunctuation() = assertEquals("I hear. Say it.", d.apply("I hear. Say it."))
    @Test fun empty() = assertEquals("hello", Dictionary.parse("").apply("hello"))
}
