package dev.offlineflow.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotwordsTest {
    @Test fun keepsNamesAndJargon() =
        assertEquals(listOf("Kushagra", "Hearsay", "Santa Clara", "GitHub"), Hotwords.terms(listOf("Kushagra", "Hearsay", "Santa  Clara", "GitHub")))

    @Test fun dropsShortAndOddTerms() =
        assertEquals(listOf("Okta"), Hotwords.terms(listOf("F5", "AI", "C++", "a/b test", "node.js", "100", "Okta")))

    @Test fun eachTermOnce() = assertEquals(listOf("Hearsay"), Hotwords.terms(listOf("Hearsay", "hearsay", " Hearsay ")))

    @Test fun capped() = assertEquals(Hotwords.MAX_TERMS, Hotwords.terms((1..500).map { "Name" + "abcdefghij"[it % 10] + "x".repeat(it % 7) + it.toString(36) }).size)

    @Test fun streamStringIsSlashSeparated() = assertEquals("Kushagra/Mugdha", Hotwords.forStream(listOf("Kushagra", "Mugdha")))

    @Test fun nothingToBoost() {
        assertNull(Hotwords.forStream(emptyList()))
        assertNull(Hotwords.forStream(listOf("a", "--")))
    }

    @Test fun vocabularyScoresAreMinusTheRow() {
        val tokens = "<unk> 0\n\u2581t 1\n\u2581th 2\nin 3\n<blk> 4\n"
        assertEquals("<unk> 0\n\u2581t -1\n\u2581th -2\nin -3\n", Hotwords.vocabulary(tokens))
    }
}
