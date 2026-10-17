package com.netgician.rplauncher

import com.netgician.rplauncher.model.AimHelp
import com.netgician.rplauncher.model.AlertItem
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.Hints
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.ocr.TextBox
import com.netgician.rplauncher.ui.PinPurpose
import com.netgician.rplauncher.ui.Screen
import com.netgician.rplauncher.ui.touchToHearOn
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
    fun helperScreensAlwaysOpenOnASingleTap() {
        val speech = LauncherConfig(visionMode = VisionMode.SPEECH)
        val helperScreens = listOf(
            Screen.Settings, Screen.Pin(PinPurpose.Unlock), Screen.Pin(PinPurpose.Set), Screen.PinReset,
            Screen.EditTiles, Screen.AddTile, Screen.PickApp, Screen.WindowCheck(Screen.Home),
        )
        for (s in helperScreens) {
            assertFalse("$s must act on one tap", touchToHearOn(speech, screenReaderOn = false, screen = s))
        }
        for (s in listOf(Screen.Home, Screen.Apps, Screen.See, Screen.Alerts, Screen.ReadText)) {
            assertTrue("$s uses touch to hear", touchToHearOn(speech, screenReaderOn = false, screen = s))
        }
        // TalkBack always takes over, and sight-first never uses touch to hear.
        assertFalse(touchToHearOn(speech, screenReaderOn = true, screen = Screen.Home))
        assertFalse(touchToHearOn(LauncherConfig(), screenReaderOn = false, screen = Screen.Home))
    }

    @Test
    fun speechModesSayHowToSelect() {
        assertEquals(
            "Sight and speech mode. Single tap to read aloud. Double tap to select.",
            Hints.modeAnnouncement(VisionMode.SIGHT_SPEECH),
        )
        assertEquals("Phone. Double tap to select.", Hints.heard("Phone", hintsOn = true))
        assertEquals("Phone", Hints.heard("Phone", hintsOn = false))
        for (m in listOf(VisionMode.SIGHT_SPEECH, VisionMode.SPEECH)) {
            assertTrue(m.label, m.explain.contains("Double tap to select"))
        }
        assertTrue("hints start on", LauncherConfig().spokenHints)
        val off = LauncherConfig(spokenHints = false)
        assertEquals(off, ConfigJson.decodeSetupFile(ConfigJson.encode(off)))
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
