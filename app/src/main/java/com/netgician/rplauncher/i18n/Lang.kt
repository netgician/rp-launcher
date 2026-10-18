package com.netgician.rplauncher.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The language RP Launcher's own words are in: English, or Spanish. It follows the phone's
 * language unless a helper picks one in Setup. Words the person didn't get from RP Launcher
 * (texts, names, app names, what the camera reads) are never translated.
 */
object Lang {
    const val ENGLISH = "en"
    const val SPANISH = "es"

    /** Observable, so every screen redraws in the new language when it changes. */
    var code by mutableStateOf(ENGLISH)
        private set

    val spanish: Boolean get() = code == SPANISH

    /** The locale for RP Launcher's own speech, numbers and dates. */
    val locale: Locale
        get() {
            val phone = Locale.getDefault()
            return if (phone.language == code) phone else Locale.forLanguageTag(if (spanish) "es-US" else "en-US")
        }

    /** [setting] is the helper's choice ("en", "es") or null for the phone's language. */
    fun choose(setting: String?, phone: Locale = Locale.getDefault()) {
        code = when (setting) {
            ENGLISH, SPANISH -> setting
            else -> if (phone.language == SPANISH) SPANISH else ENGLISH
        }
    }
}

/**
 * One of RP Launcher's own phrases, in the current language. [en] is the English text, which is
 * also the key; `%s` (or `%1$s`, `%2$s`) marks where each of [args] goes. Anything without a
 * Spanish version stays in English, so nothing is ever blank.
 */
fun tr(en: String, vararg args: Any?): String {
    val pattern = if (Lang.spanish) Spanish.words[en] ?: en else en
    if (args.isEmpty()) return pattern
    return runCatching { String.format(Lang.locale, pattern, *args) }
        .getOrElse { String.format(Locale.US, en, *args) }
}
