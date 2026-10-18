package com.netgician.rplauncher

import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.i18n.Spanish
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Every phrase RP Launcher says or shows has Spanish, with the same blanks in it. */
class LanguageTest {
    @After
    fun backToEnglish() = Lang.choose("en")

    /** The English phrases marked with tr("...") in the app's code. */
    private fun phrases(): Set<String> {
        val src = File("src/main/java")
        assertTrue("run from the app module", src.isDirectory)
        val found = mutableSetOf<String>()
        val call = Regex("""(?<![A-Za-z0-9_.])tr\("((?:[^"\\$]|\\.)*)"""")
        src.walkTopDown().filter { it.extension == "kt" && it.parentFile.name != "i18n" }.forEach { f ->
            call.findAll(f.readText()).forEach { m ->
                found += m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            }
        }
        return found
    }

    @Test
    fun everyPhraseHasSpanish() {
        val all = phrases()
        assertTrue("found the app's phrases", all.size > 500)
        val missing = all.filter { it !in Spanish.words }
        assertEquals("phrases without Spanish: $missing", emptyList<String>(), missing)
    }

    @Test
    fun blanksMatch() {
        for ((en, es) in Spanish.words) {
            val want = Regex("%(\\d\\$)?s").findAll(en).count()
            val got = Regex("%(\\d\\$)?s").findAll(es).count()
            assertEquals("blanks in \"$en\" vs \"$es\"", want, got)
        }
    }

    @Test
    fun spanishWhenChosen() {
        Lang.choose("es")
        assertEquals("Linterna encendida", tr("Torch on"))
        assertEquals("¿Llamar a Mary?", tr("Call %s?", "Mary"))
        assertEquals("Mensajes", TileSpec.Messages.label)
        assertEquals("Por la hora", AutoNight.CLOCK.label)
        assertEquals("Voz primero", VisionMode.SPEECH.label)
        assertEquals("Pequeño", LauncherConfig.TEXT_SIZES[0].name)
        assertEquals("2 minutos, 5 segundos", CallWords.spokenDuration(125))
        Lang.choose("en")
        assertEquals("Torch on", tr("Torch on"))
        assertEquals("Texts", TileSpec.Messages.label)
        assertEquals("2 minutes, 5 seconds", CallWords.spokenDuration(125))
    }

    @Test
    fun followsThePhoneUnlessChosen() {
        Lang.choose(null, Locale.forLanguageTag("es-MX"))
        assertEquals("es", Lang.code)
        Lang.choose(null, Locale.US)
        assertEquals("en", Lang.code)
        Lang.choose("es", Locale.US)
        assertEquals("es", Lang.code)
    }

    @Test
    fun untranslatedStaysEnglish() {
        Lang.choose("es")
        assertEquals("Not a phrase the app has", tr("Not a phrase the app has"))
    }
}
