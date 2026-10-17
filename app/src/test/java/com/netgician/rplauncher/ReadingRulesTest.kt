package com.netgician.rplauncher

import com.netgician.rplauncher.model.AimHelp
import com.netgician.rplauncher.model.AlertItem
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.ocr.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingRulesTest {

    @Test
    fun spanishTextGetsASpanishVoice() {
        assertEquals("es", Language.guess("Tome una tableta cada día. Niños menores de 12 años: pregunte al médico"))
        assertEquals("es", Language.guess("¿Necesita cambiarla? Llame al 919-555-0100"))
        assertEquals("es", Language.guess("Su cita es el lunes"))
    }

    @Test
    fun englishTextGetsAnEnglishVoice() {
        assertEquals("en", Language.guess("Take 2 tablets every 6 hours. Keep out of reach of children"))
        assertEquals("en", Language.guess("Your appointment is on Monday at 10:30 AM"))
        assertEquals("en", Language.guess("919-555-0100")) // nothing to go on: the phone's language
    }

    @Test
    fun noTextGivesAimingAdvice() {
        val hints = AimHelp.hints(emptyList(), 1000, 800)
        assertEquals(1, hints.size)
        assertTrue(hints[0].startsWith("No text found"))
    }

    @Test
    fun textInTheMiddleNeedsNoAdvice() {
        assertTrue(AimHelp.hints(listOf(TextBox(100, 100, 900, 200)), 1000, 800).isEmpty())
    }

    @Test
    fun textAtTheEdgesIsReported() {
        val hints = AimHelp.hints(listOf(TextBox(0, 300, 500, 360), TextBox(300, 700, 1000, 800)), 1000, 800)
        assertEquals(1, hints.size)
        val h = hints[0]
        assertTrue(h, h.contains("left") && h.contains("right") && h.contains("bottom"))
        assertFalse(h, h.contains("top"))
    }

    @Test
    fun alertsReadWhoTheyAreFrom() {
        val a = AlertItem("k", "Messages", "Mary", "Running late, see you at 6", 0L)
        assertEquals("From Messages. Mary. Running late, see you at 6", a.speech)
        assertEquals("Mary\nRunning late, see you at 6", a.shown)
        val noTitle = AlertItem("k", "Calendar", "", "Dentist at 3 PM", 0L)
        assertEquals("From Calendar. Dentist at 3 PM", noTitle.speech)
    }

    @Test
    fun visionModesChangeHowTilesRespond() {
        val sight = LauncherConfig(cols = 3)
        assertFalse(sight.touchToHear)
        assertEquals(3, sight.homeCols)
        val both = sight.copy(visionMode = VisionMode.SIGHT_SPEECH)
        assertTrue(both.touchToHear)
        assertEquals(3, both.homeCols)
        val speech = sight.copy(visionMode = VisionMode.SPEECH)
        assertTrue(speech.touchToHear)
        assertEquals("speech first is one list", 1, speech.homeCols)
    }

    @Test
    fun newSettingsSurviveTheSetupFile() {
        val c = LauncherConfig(
            visionMode = VisionMode.SPEECH,
            darkScreen = true,
            tiles = listOf(TileSpec.Alerts, TileSpec.See, TileSpec.Phone),
        )
        assertEquals(c, ConfigJson.decodeSetupFile(ConfigJson.encode(c)))
        val old = ConfigJson.decodeSetupFile("""{"version":2,"tiles":[{"type":"phone"}]}""")
        assertEquals(VisionMode.SIGHT, old.visionMode)
        assertFalse(old.darkScreen)
    }
}
