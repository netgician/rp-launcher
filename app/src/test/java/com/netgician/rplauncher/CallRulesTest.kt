package com.netgician.rplauncher

import com.netgician.rplauncher.calls.CallInfo
import com.netgician.rplauncher.calls.CallPhase
import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.calls.RecentCall
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.ui.Screen
import com.netgician.rplauncher.ui.touchToHearOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRulesTest {
    @Test
    fun callTimesReadLikeAClock_andAloudInWords() {
        assertEquals("0:05", CallWords.clock(5))
        assertEquals("2:05", CallWords.clock(125))
        assertEquals("1:00:09", CallWords.clock(3609))
        assertEquals("2 minutes, 5 seconds", CallWords.spokenDuration(125))
        assertEquals("1 minute", CallWords.spokenDuration(60))
        assertEquals("0 seconds", CallWords.spokenDuration(0))
        assertEquals("1 hour, 1 second", CallWords.spokenDuration(3601))
    }

    @Test
    fun theCallScreenSaysWhatsHappening() {
        val c = CallInfo("1", "9195550100", "Mary", CallPhase.ACTIVE, connectedAt = 1_000_000L)
        assertEquals("On call 2:05", CallWords.status(c, 1_125_000L))
        assertEquals("Calling you", CallWords.status(c.copy(phase = CallPhase.RINGING), 0))
        assertEquals("Call from Mary. Tap Answer, or Decline.", CallWords.ringing("Mary"))
        assertEquals("Call with Mary ended after 2 minutes, 5 seconds.", CallWords.ended("Mary", 125))
        assertEquals("Call with Mary ended.", CallWords.ended("Mary", 0))
    }

    @Test
    fun recentCallsSayWhoWhenAndHow() {
        fun r(k: RecentCall.Kind, d: Long = 0) = RecentCall("9195550100", "Mary", k, 0, d, k == RecentCall.Kind.MISSED)
        assertEquals("Missed call from Mary, Today at 3:05 PM.", CallWords.recent(r(RecentCall.Kind.MISSED), "Mary", "Today at 3:05 PM"))
        assertEquals("You called Mary, Today at 3:05 PM, no answer.", CallWords.recent(r(RecentCall.Kind.OUTGOING), "Mary", "Today at 3:05 PM"))
        assertEquals("Call from Mary, Today at 3:05 PM, 1 minute.", CallWords.recent(r(RecentCall.Kind.INCOMING, 60), "Mary", "Today at 3:05 PM"))
        assertEquals("Mary · missed", CallWords.recentLabel(r(RecentCall.Kind.MISSED), "Mary"))
    }

    @Test
    fun theKeypadSaysEachKey_andGroupsTheNumber() {
        assertEquals("star", CallWords.key("*"))
        assertEquals("pound", CallWords.key("#"))
        assertEquals("7", CallWords.key("7"))
        assertEquals("919 555 0100", CallWords.grouped("9195550100"))
        assertEquals("1 919 555 0100", CallWords.grouped("19195550100"))
        assertEquals("+34 612 345 678", CallWords.grouped("+34612345678"))
        assertEquals("*67", CallWords.grouped("*67"))
        assertEquals("611", CallWords.grouped("611"))
    }

    @Test
    fun phoneScreensUseTouchToHear_butSwitchingIsAHelperJob() {
        val speech = LauncherConfig(visionMode = VisionMode.SPEECH)
        for (s in listOf(Screen.PhoneHome, Screen.Dial(""), Screen.Recents)) {
            assertTrue("$s", touchToHearOn(speech, screenReaderOn = false, screen = s))
        }
        assertFalse(touchToHearOn(speech, screenReaderOn = false, screen = Screen.PhoneSwitch))
    }
}
