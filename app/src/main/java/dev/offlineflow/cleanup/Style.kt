package dev.offlineflow.cleanup

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo

/** How dictated text is written, chosen per app. */
enum class Style(val label: String) {
    /** Texting: conversational, light punctuation. */
    CASUAL("Casual"),
    /** Email, docs, work chat: complete sentences, proper punctuation. */
    POLISHED("Polished"),
    /** Code, URLs, search boxes: exactly what was said (dictionary fixes only), no LLM. */
    VERBATIM("Verbatim");

    fun next(): Style = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "hearsay"

        private val CASUAL_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
            "com.snapchat.android", "com.discord", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.reddit.frontpage", "com.google.android.apps.dynamite.chat",
        )
        private val POLISHED_APPS = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            "com.Slack", "com.microsoft.teams", "com.linkedin.android", "com.notion.id",
            "com.google.android.apps.docs.editors.docs", "com.google.android.keep", "com.microsoft.office.word",
            "com.google.android.apps.dynamite",
        )
        private val VERBATIM_APPS = setOf(
            "com.termux", "com.foxdebug.acode", "com.foxdebug.acodefree", "com.rhmsoft.code",
            "com.aide.ui", "io.spck", "com.github.android",
        )

        /** The user's choice for this app if they made one, else a default from the app and field. */
        fun forEditor(context: Context, info: EditorInfo?): Style {
            val pkg = info?.packageName
            if (pkg != null) {
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(pkg), null)
                saved?.let { s -> entries.firstOrNull { it.name == s }?.let { return it } }
            }
            return default(pkg, info?.inputType ?: 0)
        }

        fun save(context: Context, pkg: String, style: Style) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(pkg), style.name).apply()

        fun default(pkg: String?, inputType: Int): Style {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if (cls != InputType.TYPE_CLASS_TEXT) return VERBATIM // numbers, phone, dates
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_FILTER -> return VERBATIM
            }
            return when (pkg) {
                in VERBATIM_APPS -> VERBATIM
                in CASUAL_APPS -> CASUAL
                in POLISHED_APPS -> POLISHED
                else -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> CASUAL
                    else -> POLISHED
                }
            }
        }

        private fun key(pkg: String) = "style:$pkg"
    }
}
