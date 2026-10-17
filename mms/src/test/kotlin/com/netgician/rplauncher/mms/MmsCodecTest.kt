package com.netgician.rplauncher.mms

import com.netgician.rplauncher.mms.pdu.AcknowledgeInd
import com.netgician.rplauncher.mms.pdu.CharacterSets
import com.netgician.rplauncher.mms.pdu.EncodedStringValue
import com.netgician.rplauncher.mms.pdu.NotifyRespInd
import com.netgician.rplauncher.mms.pdu.PduBody
import com.netgician.rplauncher.mms.pdu.PduComposer
import com.netgician.rplauncher.mms.pdu.PduParser
import com.netgician.rplauncher.mms.pdu.PduPart
import com.netgician.rplauncher.mms.pdu.SendReq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsCodecTest {

    private fun <T> must(x: T?): T { assertNotNull(x); return x!! }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    /** Bytes written by hand, field by field, the way the MMS encoding spec lays them out. */
    private fun bytes(vararg pieces: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in pieces) when (p) {
            is Int -> out.write(p)
            is String -> { out.write(p.toByteArray(Charsets.ISO_8859_1)); out.write(0) } // text-string ends in a zero byte
            is ByteArray -> out.write(p)
            else -> error("bad piece $p")
        }
        return out.toByteArray()
    }

    /**
     * A downloaded picture text as a carrier sends it. Built with the send composer and then
     * relabelled as a retrieve-conf; the two share the same layout.
     */
    private fun received(from: String, to: List<String>, cc: List<String> = emptyList(), parts: List<PduPart>): ByteArray {
        val req = SendReq()
        req.from = EncodedStringValue(from)
        req.setTo(to.map { EncodedStringValue(it) }.toTypedArray())
        if (cc.isNotEmpty()) req.setCc(cc.map { EncodedStringValue(it) }.toTypedArray())
        req.date = 1_792_000_000L
        req.body = PduBody().apply { parts.forEach { addPart(it) } }
        val b = PduComposer(req).make()
        assertEquals(0x8C, b[0].toInt() and 0xFF)
        assertEquals(0x80, b[1].toInt() and 0xFF)
        b[1] = 0x84.toByte()
        return b
    }

    private fun part(type: String, name: String, data: ByteArray, charset: Int = 0) = PduPart().apply {
        contentType = type.toByteArray()
        if (charset != 0) this.charset = charset
        this.name = name.toByteArray()
        contentLocation = name.toByteArray()
        contentId = "<$name>".toByteArray()
        this.data = data
    }

    @Test
    fun aPictureTextWaitingIsRead() {
        val push = bytes(
            0x8C, 0x82,                                   // message type: notification
            0x98, "T17a2b",                               // transaction id
            0x8D, 0x92,                                   // version 1.2
            0x89, 0x18, 0x80, "+19195550100/TYPE=PLMN",   // from (length 24: token + 22 chars + zero)
            0x96, "Lunch",                                // subject
            0x8A, 0x80,                                   // class: personal
            0x8E, 0x02, 0x30, 0x39,                       // size: 12345 bytes
            0x88, 0x05, 0x81, 0x03, 0x09, 0x3A, 0x80,     // expiry: 7 days from now
            0x83, "http://mmsc.example.com/m/abc",        // where to fetch it
        )
        val n = must(MmsCodec.readNotice(push))
        assertEquals("http://mmsc.example.com/m/abc", n.contentLocation)
        assertEquals("+19195550100", n.from)
        assertEquals("Lunch", n.subject)
        assertEquals(12345L, n.sizeBytes)
        val inAWeek = System.currentTimeMillis() / 1000 + 604_800L
        assertTrue("expires a week from now", kotlin.math.abs(n.expiresAtSeconds - inAWeek) < 60)
        assertArrayEquals("T17a2b".toByteArray(), n.transactionId)
        assertEquals(MmsCodec.VERSION_1_2, n.mmsVersion)
        assertEquals(0x82, MmsCodec.messageType(push))
    }

    @Test
    fun brokenOrForeignBytesNeverCrash() {
        assertNull(MmsCodec.readNotice(ByteArray(0)))
        assertNull(MmsCodec.readNotice(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(MmsCodec.readMessage(byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x00)))
        assertNull(MmsCodec.readSendResult(ByteArray(64) { it.toByte() }))
        val push = bytes(0x8C, 0x82, 0x98, "T1", 0x8D, 0x92) // missing where to fetch it
        assertNull(MmsCodec.readNotice(push))
        assertEquals(-1, MmsCodec.messageType(byteArrayOf(9, 9)))
    }

    @Test
    fun aGroupPictureTextIsReadWithEveryPart() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana López\r\nTEL;TYPE=CELL:+1 919 555 0142\r\nEND:VCARD\r\n"
        val msg = received(
            from = "+19195550100",
            to = listOf("+19195550199", "+19195550111"),
            cc = listOf("ana@example.com"),
            parts = listOf(
                part("application/smil", "smil.xml", "<smil/>".toByteArray()),
                part("image/jpeg", "IMG_0001.jpg", jpeg),
                part("text/plain", "text_0.txt", "On my way! Mañana 🙂".toByteArray(Charsets.UTF_8), CharacterSets.UTF_8),
                part("audio/amr", "voice.amr", byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52)),
                part("video/mp4", "clip.mp4", byteArrayOf(0, 0, 0, 0x18)),
                part("text/x-vcard", "ana.vcf", vcard.toByteArray(Charsets.UTF_8), CharacterSets.UTF_8),
                part("application/octet-stream", "invite.ics", "BEGIN:VCALENDAR".toByteArray()),
                part("application/vnd.oma.drm.message", "song.dm", byteArrayOf(7)),
            ),
        )
        val m = must(MmsCodec.readMessage(msg))
        assertEquals("+19195550100", m.from)
        assertEquals(listOf("+19195550199", "+19195550111"), m.to)
        assertEquals(listOf("ana@example.com"), m.cc)
        assertEquals(1_792_000_000L, m.dateSeconds)
        assertEquals("On my way! Mañana 🙂", m.text)
        assertEquals(
            listOf(PartKind.IMAGE, PartKind.AUDIO, PartKind.VIDEO, PartKind.CONTACT, PartKind.EVENT, PartKind.OTHER),
            m.attachments.map { it.kind },
        )
        assertArrayEquals(jpeg, m.attachments[0].data)
        assertEquals("IMG_0001.jpg", m.attachments[0].fileName)
        assertEquals("Ana López", ContactCard.read(m.attachments[3].asText()).single().name)

        // My own number is left out, however it's written; everyone else is in the group.
        val others = m.others(listOf("(919) 555-0199"))
        assertEquals(listOf("+19195550100", "+19195550111", "ana@example.com"), others)
    }

    @Test
    fun aOneToOnePictureTextIsNotAGroup() {
        val msg = received("+19195550100/TYPE=PLMN", listOf("+19195550199/TYPE=PLMN"),
            parts = listOf(part("image/png", "a.png", byteArrayOf(1))))
        val m = must(MmsCodec.readMessage(msg))
        assertEquals(listOf("+19195550100"), m.others(listOf("9195550199")))
        assertEquals("", m.text)
    }

    @Test
    fun aGroupTextWeSendReachesEveryone() {
        val bytes = must(
            MmsCodec.buildSend(
                recipients = listOf("(919) 555-0100", "+1 919 555 0111", "919-555-0100", "ana@example.com"),
                text = "  On my way  ",
                attachments = listOf(Attachment("image/jpeg", "photo.jpg", jpeg), Attachment("image/jpeg", "photo.jpg", jpeg)),
                subject = "Hi",
                nowSeconds = 1_792_000_000L,
            ),
        )
        val req = PduParser(bytes, true).parse() as SendReq
        assertEquals(
            "the same number written twice goes once",
            listOf("9195550100", "+19195550111", "ana@example.com"),
            req.to.map { Addresses.clean(it.string) },
        )
        assertEquals("Hi", req.subject.string)
        assertEquals(1_792_000_000L, req.date)
        val body = req.body
        val types = (0 until body.partsNum).map { String(body.getPart(it).contentType) }
        assertEquals(listOf("application/smil", "image/jpeg", "image/jpeg", "text/plain"), types)
        val names = (1 until body.partsNum).map { String(body.getPart(it).contentLocation) }
        assertEquals("every part has its own name", names.size, names.toSet().size)
        assertEquals("On my way", String(body.getPart(3).data, Charsets.UTF_8))
        assertEquals(CharacterSets.UTF_8, body.getPart(3).charset)
        val smil = String(body.getPart(0).data)
        names.forEach { assertTrue("$it is in the layout", smil.contains("src=\"$it\"")) }
        assertArrayEquals(jpeg, body.getPart(1).data)
    }

    @Test
    fun aVoiceMessageAloneIsFine_butNothingToSendIsRefused() {
        assertNotNull(MmsCodec.buildSend(listOf("9195550100"), null, listOf(Attachment("audio/mp4", null, byteArrayOf(1, 2)))))
        assertNull(MmsCodec.buildSend(listOf("9195550100"), "   ", emptyList()))
        assertNull(MmsCodec.buildSend(emptyList(), "Hello", emptyList()))
        assertNull(MmsCodec.buildSend(listOf("()-"), "Hello", emptyList()))
    }

    @Test
    fun theCarrierIsToldWhatHappened() {
        val tid = "T17a2b".toByteArray()
        val resp = PduParser(must(MmsCodec.notifyResponse(tid, MmsCodec.VERSION_1_2, MmsCodec.STATUS_RETRIEVED)), true).parse() as NotifyRespInd
        assertArrayEquals(tid, resp.transactionId)
        assertEquals(MmsCodec.STATUS_RETRIEVED, resp.status)
        val deferred = PduParser(must(MmsCodec.notifyResponse(tid, 0, MmsCodec.STATUS_DEFERRED)), true).parse() as NotifyRespInd
        assertEquals(MmsCodec.STATUS_DEFERRED, deferred.status)
        val ack = PduParser(must(MmsCodec.acknowledge(tid, MmsCodec.VERSION_1_2)), true).parse() as AcknowledgeInd
        assertArrayEquals(tid, ack.transactionId)
    }

    @Test
    fun sendResultsSayWhatWentWrong() {
        fun conf(status: Int) = bytes(0x8C, 0x81, 0x98, "T1", 0x8D, 0x92, 0x92, status, 0x8B, "msg-42")
        val ok = must(MmsCodec.readSendResult(conf(0x80)))
        assertTrue(ok.ok)
        assertEquals("msg-42", ok.messageId)
        val tooBig = must(MmsCodec.readSendResult(conf(0xE5)))
        assertFalse(tooBig.ok)
        assertFalse(tooBig.worthRetrying)
        assertTrue(tooBig.reason, tooBig.reason.contains("too big"))
        val busy = must(MmsCodec.readSendResult(conf(0xC3)))
        assertTrue(busy.worthRetrying)
        assertTrue(busy.reason.contains("busy"))
        val denied = must(MmsCodec.readSendResult(conf(0xE1)))
        assertFalse(denied.worthRetrying)
        assertTrue(denied.reason.contains("Picture texts may be off"))
    }

    @Test
    fun deliveryReportsAreRead() {
        fun ind(status: Int) = bytes(
            0x8C, 0x86, 0x8D, 0x92, 0x8B, "msg-42", 0x97, "+19195550100/TYPE=PLMN",
            0x85, 0x04, 0x6A, 0xD0, 0x3C, 0x00, 0x95, status,
        )
        val d = must(MmsCodec.readDelivery(ind(0x81)))
        assertTrue(d.delivered)
        assertEquals("msg-42", d.messageId)
        assertEquals(listOf("+19195550100"), d.to)
        assertTrue(must(MmsCodec.readDelivery(ind(0x87))).failed)
    }

    @Test
    fun partsAreSortedByWhatTheyHold() {
        assertEquals(PartKind.IMAGE, PartKinds.of("image/heic", null))
        assertEquals(PartKind.IMAGE, PartKinds.of("application/octet-stream", "IMG_2041.JPG"))
        assertEquals(PartKind.VIDEO, PartKinds.of("application/octet-stream", "clip.mov"))
        assertEquals(PartKind.AUDIO, PartKinds.of("application/ogg", null))
        assertEquals(PartKind.AUDIO, PartKinds.of("audio/amr", "x"))
        assertEquals(PartKind.CONTACT, PartKinds.of("text/vcard", null))
        assertEquals(PartKind.CONTACT, PartKinds.of("application/octet-stream", "Mary.vcf"))
        assertEquals(PartKind.EVENT, PartKinds.of("text/calendar", null))
        assertEquals(PartKind.TEXT, PartKinds.of("TEXT/PLAIN; charset=utf-8", null))
        assertEquals(PartKind.LAYOUT, PartKinds.of("application/smil", null))
        assertEquals(PartKind.OTHER, PartKinds.of("application/vnd.oma.drm.message", "song.dm"))
        assertEquals(PartKind.OTHER, PartKinds.of("application/octet-stream", null))
    }

    @Test
    fun wordsAreDecodedInTheirOwnCharacterSet() {
        assertEquals("Mañana", MmsPart("text/plain", null, "Mañana".toByteArray(Charsets.ISO_8859_1), CharacterSets.ISO_8859_1).asText())
        assertEquals("Mañana", MmsPart("text/plain", null, "Mañana".toByteArray(Charsets.UTF_8)).asText())
        assertEquals("Hola", MmsPart("text/plain", null, "Hola".toByteArray(Charsets.UTF_16), CharacterSets.UTF_16).asText())
        assertEquals("Hi there\nBye & see you",
            MmsPart("text/html", null, "<p>Hi <b>there</b></p>Bye &amp; see you".toByteArray()).asText())
    }

    @Test
    fun numbersMatchHoweverTheyAreWritten() {
        assertEquals("+19195550100", Addresses.clean("+19195550100/TYPE=PLMN"))
        assertTrue(Addresses.same("+1 (919) 555-0100", "9195550100"))
        assertTrue(Addresses.same("Ana@Example.com", "ana@example.com"))
        assertFalse(Addresses.same("9195550100", "9195550101"))
        assertFalse(Addresses.same("55501", "9195555501"))
        assertTrue(Addresses.same("611", "611"))
        assertEquals("+19195550100", Addresses.forSending("+1 (919) 555-0100"))
        assertEquals("9195550100", Addresses.forSending("919.555.0100"))
    }

    @Test
    fun savedFileNamesAreSafe() {
        assertEquals("IMG_0001.jpg", MmsPart("image/jpeg", "IMG_0001.jpg", jpeg).safeFileName(0))
        assertEquals("part3.jpg", MmsPart("image/jpeg", null, jpeg).safeFileName(3))
        assertEquals("passwd.amr", MmsPart("audio/amr", "../../etc/passwd", jpeg).safeFileName(0))
        assertEquals("Foto_de_Ana.jpg", MmsPart("image/jpeg", "<Foto de Ana>", jpeg).safeFileName(0))
    }
}
