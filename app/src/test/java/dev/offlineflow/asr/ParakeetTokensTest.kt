package dev.offlineflow.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class ParakeetTokensTest {
    // tokens.txt format: "<piece> <id>", ▁ marks a word start, blank last.
    private val tok = ParakeetTokens.parse(listOf("<unk> 0", "▁I 1", "▁fin 2", "ished 3", ". 4", "▁Sam 5", "<blk> 6", ""))

    @Test fun textDropsLeadingSpace() {
        assertEquals("I finished. Sam", tok.text(intArrayOf(1, 2, 3, 4, 5)))
        assertEquals(7, tok.size)
    }

    @Test fun wordsSpanTheirTokens() {
        val w = tok.words(intArrayOf(1, 2, 3, 4, 5), intArrayOf(2, 4, 6, 7, 9))
        assertEquals(listOf(WordSpan("I", 2, 2), WordSpan("finished.", 4, 7), WordSpan("Sam", 9, 9)), w)
    }

    @Test fun empty() {
        assertEquals("", tok.text(IntArray(0)))
        assertEquals(emptyList<WordSpan>(), tok.words(IntArray(0), IntArray(0)))
    }
}
