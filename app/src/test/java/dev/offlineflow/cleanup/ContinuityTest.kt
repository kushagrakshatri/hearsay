package dev.offlineflow.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuityTest {
    @Test fun continuesSentence() = assertEquals("and bring snacks.", Continuity.apply("And bring snacks.", "I'll be there "))
    @Test fun afterComma() = assertEquals("but not today.", Continuity.apply("But not today.", "Sure,"))
    @Test fun newSentence() = assertEquals("And bring snacks.", Continuity.apply("And bring snacks.", "See you. "))
    @Test fun emptyField() = assertEquals("And then.", Continuity.apply("And then.", ""))
    @Test fun keepsNames() = assertEquals("Sarah said hi.", Continuity.apply("Sarah said hi.", "and "))
    @Test fun keepsI() = assertEquals("I think so.", Continuity.apply("I think so.", "well "))
    @Test fun keepsAcronyms() = assertEquals("API is down.", Continuity.apply("API is down.", "the "))
}
