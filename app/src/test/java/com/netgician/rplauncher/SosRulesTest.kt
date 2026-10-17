package com.netgician.rplauncher

import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Place
import com.netgician.rplauncher.model.Sos
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.ui.NumberPurpose
import com.netgician.rplauncher.ui.Screen
import com.netgician.rplauncher.ui.touchToHearOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosRulesTest {
    private val here = Place(35.77961, -78.63818, 18f, 0L, "12 Oak Street, Raleigh, NC")

    @Test
    fun theSosTextSaysWhereAndHowSure() {
        assertEquals(
            "SOS: I need help. I'm near 12 Oak Street, Raleigh, NC. Map: https://maps.google.com/?q=35.77961,-78.63818 " +
                "(within 15 m, 3:05 PM). Sent by RP Launcher.",
            Sos.message(here, "3:05 PM"),
        )
        val noStreet = here.copy(address = null, accuracyMeters = null)
        assertEquals(
            "SOS: I need help. Map: https://maps.google.com/?q=35.77961,-78.63818 (3:05 PM). Sent by RP Launcher.",
            Sos.message(noStreet, "3:05 PM"),
        )
        assertEquals("SOS: I need help. My location isn't available. Sent by RP Launcher.", Sos.message(null, "3:05 PM"))
        assertTrue(Sos.message(here, "15:05", "es").startsWith("SOS: Necesito ayuda. Estoy cerca de 12 Oak Street"))
    }

    @Test
    fun mapLinksUseDotsWhateverThePhoneLanguage() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("https://maps.google.com/?q=35.77961,-78.63818", Sos.mapLink(35.779611, -78.638184))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun whereAmISaysTheStreetAndHowSure() {
        assertEquals("You're near 12 Oak Street, Raleigh, NC. Accurate to about 15 meters.", Sos.spokenPlace(here))
        assertEquals("You're near 12 Oak Street, Raleigh, NC. This is only a rough area.", Sos.spokenPlace(here.copy(accuracyMeters = 1500f)))
        assertEquals("Your location isn't available right now.", Sos.spokenPlace(null))
        assertEquals("There's no street address for this spot.", Sos.spokenPlace(here.copy(address = null, accuracyMeters = null)))
    }

    @Test
    fun theCountdownSaysWhoAndHowToStop() {
        assertEquals(
            "SOS. Texting Mary and Bob with your location in 5 seconds. Tap Cancel to stop.",
            Sos.countdownWords(5, listOf("Mary", "Bob"), practice = false),
        )
        assertTrue(Sos.countdownWords(5, listOf("Mary"), practice = true).startsWith("Practice SOS. Nothing will really be sent."))
    }

    @Test
    fun emergencyNumbersByCountry() {
        assertEquals("911", Sos.emergencyNumber("us"))
        assertEquals("911", Sos.emergencyNumber("MX"))
        assertEquals("999", Sos.emergencyNumber("GB"))
        assertEquals("112", Sos.emergencyNumber("ES"))
        assertEquals("112", Sos.emergencyNumber(""))
    }

    @Test
    fun sosCancelsWithOneTapEvenInSpeechModes() {
        val speech = LauncherConfig(visionMode = VisionMode.SPEECH)
        assertFalse(touchToHearOn(speech, screenReaderOn = false, screen = Screen.Sos(practice = false)))
        assertFalse(touchToHearOn(speech, screenReaderOn = false, screen = Screen.SosSetup))
        assertFalse("typing this phone's number is a helper job",
            touchToHearOn(speech, screenReaderOn = false, screen = Screen.NumberPad(NumberPurpose.OWN_NUMBER)))
        // The person's own texting screens use touch to hear, like the home screen.
        for (s in listOf(Screen.Texts, Screen.Conversation(1), Screen.Compose, Screen.WhereAmI, Screen.NumberPad(NumberPurpose.TEXT))) {
            assertTrue("$s uses touch to hear", touchToHearOn(speech, screenReaderOn = false, screen = s))
        }
    }

    @Test
    fun sosContactsSurviveTheSetupFile() {
        val c = LauncherConfig(
            tiles = listOf(TileSpec.Sos, TileSpec.WhereAmI, TileSpec.Messages),
            sosContacts = listOf(TileSpec.Contact("Mary", "+19195550100"), TileSpec.Contact("Bob", "9195550111")),
            sosSeconds = 10,
        )
        assertEquals(c, ConfigJson.decodeSetupFile(ConfigJson.encode(c)))
        val old = ConfigJson.decodeSetupFile("""{"version":3,"tiles":[{"type":"phone"}],"sosSeconds":7}""")
        assertTrue(old.sosContacts.isEmpty())
        assertEquals("unknown countdowns fall back to 5", 5, old.sosSeconds)
    }
}
