package com.netgician.rplauncher

import android.app.Activity
import android.telephony.SmsManager
import com.netgician.rplauncher.mms.PartKind
import com.netgician.rplauncher.texts.Box
import com.netgician.rplauncher.texts.ComposeLink
import com.netgician.rplauncher.texts.Conversation
import com.netgician.rplauncher.texts.DownloadState
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.PhotoFit
import com.netgician.rplauncher.texts.Problems
import com.netgician.rplauncher.texts.QuickReplies
import com.netgician.rplauncher.texts.Retry
import com.netgician.rplauncher.texts.TextMessage
import com.netgician.rplauncher.texts.TextPart
import com.netgician.rplauncher.texts.TextSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class TextRulesTest {
    private val time: (Long) -> String = { "3:05 PM" }

    private fun at(y: Int, mo: Int, d: Int, h: Int = 15): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, 5) }.timeInMillis

    @Test
    fun namesAreJoinedTheWayPeopleSayThem() {
        assertEquals("Unknown", People.join(emptyList()))
        assertEquals("Mary", People.join(listOf("Mary")))
        assertEquals("Mary and Bob", People.join(listOf("Mary", "Bob")))
        assertEquals("Mary, Bob and Ana", People.join(listOf("Mary", "Bob", "Ana")))
        assertEquals("Mary, Bob and 3 others", People.join(listOf("Mary", "Bob", "Ana", "Joe", "Sue")))
        assertEquals("Mary", People.spoken("Mary"))
        assertEquals("9 1 9, 5 5 5, 0 1 0 0", People.spoken("(919) 555-0100"))
    }

    @Test
    fun everyFailureHasPlainWords() {
        assertEquals("Sent", Problems.sms(Activity.RESULT_OK))
        assertEquals("No signal.", Problems.sms(SmsManager.RESULT_ERROR_NO_SERVICE))
        assertTrue(Problems.sms(SmsManager.RESULT_ERROR_RADIO_OFF).contains("airplane mode"))
        assertTrue(Problems.sms(9999).contains("carrier"))
        assertTrue(Problems.mms(SmsManager.MMS_ERROR_NO_DATA_NETWORK).contains("mobile data"))
        assertTrue(Problems.mms(SmsManager.MMS_ERROR_INVALID_APN).contains("settings"))
        assertTrue(Problems.mms(SmsManager.MMS_ERROR_HTTP_FAILURE).contains("signal"))
        // Missing carrier settings won't fix themselves, so those aren't retried.
        assertFalse(Problems.mmsWorthRetrying(SmsManager.MMS_ERROR_INVALID_APN))
        assertTrue(Problems.mmsWorthRetrying(SmsManager.MMS_ERROR_IO_ERROR))
        assertTrue(Problems.mmsWorthRetrying(SmsManager.MMS_ERROR_NO_DATA_NETWORK))
    }

    @Test
    fun downloadsAreTriedThreeMoreTimesThenReported() {
        assertEquals(1L, Retry.nextDelay(1))
        assertEquals(5L, Retry.nextDelay(2))
        assertEquals(30L, Retry.nextDelay(3))
        assertNull("after the last try, the person is told", Retry.nextDelay(4))
    }

    @Test
    fun timesAreSaidLikeAPersonWould() {
        val now = at(2026, 10, 17)
        assertEquals("Today at 3:05 PM", TextSpeech.whenSaid(at(2026, 10, 17, 9), now, Locale.US, time))
        assertEquals("Yesterday at 3:05 PM", TextSpeech.whenSaid(at(2026, 10, 16, 23), now, Locale.US, time))
        assertEquals("Tuesday at 3:05 PM", TextSpeech.whenSaid(at(2026, 10, 13), now, Locale.US, time))
        assertEquals("March 3 at 3:05 PM", TextSpeech.whenSaid(at(2026, 3, 3), now, Locale.US, time))
        assertEquals("December 24, 2025 at 3:05 PM", TextSpeech.whenSaid(at(2025, 12, 24), now, Locale.US, time))
        assertEquals("Hoy a las 3:05 PM", TextSpeech.whenSaid(at(2026, 10, 17), now, Locale.forLanguageTag("es"), time))
    }

    @Test
    fun attachmentsAreCounted() {
        fun p(k: PartKind) = TextPart(1, k, "x", null, "")
        assertEquals("", TextSpeech.attachments(emptyList()))
        assertEquals("A picture.", TextSpeech.attachments(listOf(p(PartKind.IMAGE))))
        assertEquals("2 pictures and a voice or sound clip.",
            TextSpeech.attachments(listOf(p(PartKind.IMAGE), p(PartKind.IMAGE), p(PartKind.AUDIO))))
        assertEquals("A contact card, a calendar invite and a video.",
            TextSpeech.attachments(listOf(p(PartKind.CONTACT), p(PartKind.EVENT), p(PartKind.VIDEO))))
    }

    @Test
    fun messagesAreReadWhoWhenWhatThenHowItWent() {
        val inbound = TextMessage(1, false, Box.INBOX, 0, "Running late", from = "+19195550100")
        assertEquals("From Mary, Today at 3:05 PM. Running late",
            TextSpeech.message(inbound, "Mary", group = false, whenText = "Today at 3:05 PM"))
        assertEquals("From Bob, in the group, Today at 3:05 PM. Running late",
            TextSpeech.message(inbound, "Bob", group = true, whenText = "Today at 3:05 PM"))

        val sent = TextMessage(2, false, Box.SENT, 0, "On my way", from = null, delivered = true)
        assertEquals("You sent, Today at 3:05 PM. On my way. Delivered.",
            TextSpeech.message(sent, null, group = false, whenText = "Today at 3:05 PM"))

        val failed = sent.copy(box = Box.FAILED, delivered = false, problem = "No signal.")
        val f = TextSpeech.message(failed, null, group = false, whenText = "Today at 3:05 PM")
        assertTrue(f, f.endsWith("Not sent. No signal. Double tap Try again."))

        val picture = TextMessage(3, true, Box.INBOX, 0, "", from = "x",
            parts = listOf(TextPart(9, PartKind.IMAGE, "image/jpeg", null, "")))
        assertTrue(TextSpeech.message(picture, "Mary", false, "now").endsWith("A picture."))

        val waiting = TextMessage(4, true, Box.WAITING, 0, "", from = "x", download = DownloadState.FAILED,
            problem = "Picture texts need mobile data. Check that mobile data is on.")
        val w = TextSpeech.message(waiting, "Mary", false, "now")
        assertTrue(w, w.contains("didn't download") && w.contains("mobile data") && w.endsWith("Double tap Try again."))
    }

    @Test
    fun conversationsSayWhoAndHowManyNew() {
        val c = Conversation(1, listOf("1", "2"), listOf("Mary", "Bob"), "See you at 6", 0, unread = 2, hasProblem = false)
        assertTrue(c.isGroup)
        assertEquals("Mary and Bob", c.title)
        assertEquals("Group: Mary, Bob. 2 new. See you at 6", TextSpeech.conversation(c))
        val one = c.copy(addresses = listOf("1"), names = listOf("Mary"), unread = 0, hasProblem = true)
        assertEquals("Mary. A text was not sent. See you at 6", TextSpeech.conversation(one))
    }

    @Test
    fun linksFromOtherAppsAreUnderstood() {
        assertEquals(ComposeLink.Parsed(listOf("5551234"), null), ComposeLink.parse("5551234"))
        assertEquals(ComposeLink.Parsed(listOf("5551234", "5559876"), "Hi there"), ComposeLink.parse("5551234;5559876?body=Hi there"))
        assertEquals(ComposeLink.Parsed(listOf("+15551234"), null), ComposeLink.parse("//+15551234"))
        assertEquals(ComposeLink.Parsed(emptyList(), "Hello"), ComposeLink.parse("?body=Hello"))
        assertEquals(ComposeLink.Parsed(emptyList(), null), ComposeLink.parse(null))
    }

    @Test
    fun quickRepliesComeInTheirOwnLanguage() {
        assertEquals("On my way", QuickReplies.forLanguage("en").first())
        assertEquals("Voy en camino", QuickReplies.forLanguage("es").first())
    }

    @Test
    fun photosAreSqueezedUnderTheCarrierLimit() {
        assertTrue(PhotoFit.steps.zipWithNext().all { (a, b) -> a.longSide > b.longSide })
        val budget = PhotoFit.budget(1_048_576, 500)
        assertTrue(budget in 900_000..970_000)
        assertEquals("tiny limits still leave room for a small photo", 30_000, PhotoFit.budget(10_000, 0))
    }
}
