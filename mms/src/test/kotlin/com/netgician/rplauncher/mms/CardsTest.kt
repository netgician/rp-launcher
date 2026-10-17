package com.netgician.rplauncher.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class CardsTest {
    private val eastern = ZoneId.of("America/New_York")

    @Test
    fun aModernContactCardIsRead() {
        val vcf = """
            BEGIN:VCARD
            VERSION:3.0
            N:López;Ana;María;Dr.;
            FN:Dr. Ana María López
            ORG:Clínica Norte;Eye care
            TEL;TYPE=CELL,VOICE:+1 919 555 0142
            TEL;TYPE=WORK:(919) 555-0100
            item1.EMAIL;TYPE=INTERNET:ana@example.com
            ADR;TYPE=HOME:;;12 Oak St\, Apt 3;Raleigh;NC;27601;USA
            NOTE:Call after 5\nweekdays
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")
        val c = ContactCard.read(vcf).single()
        assertEquals("Dr. Ana María López", c.name)
        assertEquals(listOf(CardPhone("+1 919 555 0142", "Mobile"), CardPhone("(919) 555-0100", "Work")), c.phones)
        assertEquals(listOf("ana@example.com"), c.emails)
        assertEquals("Clínica Norte, Eye care", c.organization)
        assertEquals("12 Oak St, Apt 3, Raleigh, NC, 27601, USA", c.address)
        assertEquals("Call after 5\nweekdays", c.note)
        assertTrue(c.speech, c.speech.startsWith("Contact card. Dr. Ana María López. Clínica Norte, Eye care. Mobile plus 1, 9 1 9, 5 5 5, 0 1 4 2."))
        assertTrue(c.speech.contains("Work 9 1 9, 5 5 5, 0 1 0 0."))
    }

    @Test
    fun anOldStyleContactCardIsRead() {
        // vCard 2.1, as older phones send it: bare types, quoted-printable with soft line breaks.
        val vcf = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Mu=C3=B1oz;Jos=C3=A9;;;\r\n" +
            "TEL;CELL:9195550177\r\nTEL;HOME:9195550188\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE:Long note that=\r\n continues here\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Second\r\nTEL:611\r\nEND:VCARD\r\n"
        val cards = ContactCard.read(vcf)
        assertEquals(2, cards.size)
        assertEquals("José Muñoz", cards[0].name)
        assertEquals(listOf("Mobile", "Home"), cards[0].phones.map { it.kind })
        assertEquals("Long note that continues here", cards[0].note)
        assertEquals("Second", cards[1].name)
        assertEquals("Phone", cards[1].phones.single().kind)
    }

    @Test
    fun foldedLinesAndEmptyCards() {
        val vcf = "BEGIN:VCARD\nVERSION:4.0\nFN:A very long na\n me that was folded\nTEL;VALUE=uri;TYPE=\"voice,cell\":tel:+1-919-555-0100\nEND:VCARD\nBEGIN:VCARD\nEND:VCARD\n"
        val c = ContactCard.read(vcf)
        assertEquals(1, c.size)
        assertEquals("A very long name that was folded", c[0].name)
        assertEquals(CardPhone("+1-919-555-0100", "Mobile"), c[0].phones.single())
        assertTrue(ContactCard.read("not a card at all").isEmpty())
    }

    @Test
    fun numbersAreReadInGroups() {
        assertEquals("9 1 9, 5 5 5, 0 1 0 0", ContactCard.spacedNumber("(919) 555-0100"))
        assertEquals("plus 1, 9 1 9, 5 5 5, 0 1 0 0", ContactCard.spacedNumber("+1 919 555 0100"))
        assertEquals("6 1 1", ContactCard.spacedNumber("611"))
        assertEquals("plus 3 4 6, 1 2 3, 4 5 6, 7 8", ContactCard.spacedNumber("+34 612 345 678"))
    }

    @Test
    fun anInviteWithATimeZoneIsRead() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:Eye doctor\, Dr. Patel
            DTSTART;TZID=America/Los_Angeles:20261020T140000
            DTEND;TZID=America/Los_Angeles:20261020T150000
            LOCATION:Suite 200\, 1 Main St
            DESCRIPTION:Bring your glasses.
            BEGIN:VALARM
            TRIGGER:-PT30M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val e = CalendarInvite.read(ics, eastern).single()
        assertEquals("Eye doctor, Dr. Patel", e.title)
        assertFalse(e.allDay)
        val start = ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        assertEquals(start, e.startMillis)
        assertEquals(start + 3_600_000L, e.endMillis)
        assertEquals("Suite 200, 1 Main St", e.location)
        assertEquals("the alarm's description isn't the event's", "Bring your glasses.", e.description)
        // Read out in the phone's own time zone: 2 PM in Los Angeles is 5 PM in New York.
        val s = e.speech(eastern, Locale.US)
        assertTrue(s, s.startsWith("Calendar invite. Eye doctor, Dr. Patel. Tuesday, October 20, 2026 at 5:00"))
        assertTrue(s, s.endsWith("At Suite 200, 1 Main St. Bring your glasses."))
    }

    @Test
    fun utcFloatingAllDayAndDurations() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Call\nDTSTART:20261020T180000Z\nDURATION:PT1H30M\nEND:VEVENT\n" +
            "BEGIN:VEVENT\nSUMMARY:Birthday\nDTSTART;VALUE=DATE:20261101\nEND:VEVENT\n" +
            "BEGIN:VEVENT\nSUMMARY:Lunch\nDTSTART:20261021T120000\nEND:VEVENT\nEND:VCALENDAR\n"
        val (call, birthday, lunch) = CalendarInvite.read(ics, eastern)
        val callStart = ZonedDateTime.of(2026, 10, 20, 18, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(callStart, call.startMillis)
        assertEquals(callStart + 90 * 60_000L, call.endMillis)
        assertTrue(birthday.allDay)
        assertEquals("Sunday, November 1, 2026", birthday.whenText(birthday.startMillis!!, eastern, Locale.US))
        assertEquals(birthday.startMillis!! + 86_400_000L, birthday.endMillis)
        assertEquals("no zone given: the phone's own",
            ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, eastern).toInstant().toEpochMilli(), lunch.startMillis)
    }

    @Test
    fun spanishInvitesReadInSpanish() {
        val e = CalendarInvite.read("BEGIN:VEVENT\nSUMMARY:Cita\nDTSTART:20261020T150000\nEND:VEVENT", eastern).single()
        val s = e.speech(eastern, Locale.forLanguageTag("es-US"))
        assertTrue(s, s.contains("martes") && s.contains("a las"))
    }

    @Test
    fun brokenInvitesDoNotCrash() {
        val e = CalendarInvite.read("BEGIN:VEVENT\nSUMMARY:Party\nDTSTART:soon\nEND:VEVENT", eastern).single()
        assertEquals("Party", e.title)
        assertNull(e.startMillis)
        assertEquals("Calendar invite. Party.", e.speech(eastern, Locale.US))
        assertTrue(CalendarInvite.read("", eastern).isEmpty())
    }
}
