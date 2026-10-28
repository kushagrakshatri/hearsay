package dev.offlineflow.cleanup

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun messagingIsCasual() = assertEquals(Style.CASUAL, Style.default("com.whatsapp", text))
    @Test fun gmailIsPolished() = assertEquals(Style.POLISHED, Style.default("com.google.android.gm", text))
    @Test fun termuxIsVerbatim() = assertEquals(Style.VERBATIM, Style.default("com.termux", text))
    @Test fun urlFieldIsVerbatim() =
        assertEquals(Style.VERBATIM, Style.default("com.android.chrome", text or InputType.TYPE_TEXT_VARIATION_URI))
    @Test fun numberFieldIsVerbatim() = assertEquals(Style.VERBATIM, Style.default("com.whatsapp", InputType.TYPE_CLASS_NUMBER))
    @Test fun unknownShortMessageIsCasual() =
        assertEquals(Style.CASUAL, Style.default("x.y", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
    @Test fun unknownIsPolished() = assertEquals(Style.POLISHED, Style.default("x.y", text))
    @Test fun cycles() = assertEquals(Style.CASUAL, Style.VERBATIM.next())
    @Test fun verbatimKeepsWords() = assertEquals("github.com/kushagra", RulesCleaner.verbatim("[BLANK_AUDIO] github.com/kushagra."))
}
