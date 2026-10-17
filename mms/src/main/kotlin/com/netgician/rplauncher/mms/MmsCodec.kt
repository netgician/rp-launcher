package com.netgician.rplauncher.mms

import com.netgician.rplauncher.mms.pdu.AcknowledgeInd
import com.netgician.rplauncher.mms.pdu.CharacterSets
import com.netgician.rplauncher.mms.pdu.DeliveryInd
import com.netgician.rplauncher.mms.pdu.EncodedStringValue
import com.netgician.rplauncher.mms.pdu.NotificationInd
import com.netgician.rplauncher.mms.pdu.NotifyRespInd
import com.netgician.rplauncher.mms.pdu.PduBody
import com.netgician.rplauncher.mms.pdu.PduComposer
import com.netgician.rplauncher.mms.pdu.PduHeaders
import com.netgician.rplauncher.mms.pdu.PduParser
import com.netgician.rplauncher.mms.pdu.PduPart
import com.netgician.rplauncher.mms.pdu.RetrieveConf
import com.netgician.rplauncher.mms.pdu.SendConf
import com.netgician.rplauncher.mms.pdu.SendReq
import java.nio.charset.Charset

/** What a part of a picture text holds, which decides how it is shown and read. */
enum class PartKind { TEXT, IMAGE, AUDIO, VIDEO, CONTACT, EVENT, LAYOUT, OTHER }

/** One piece of a picture text: words, a picture, a voice clip, a contact card... */
class MmsPart(
    /** Lower case, without parameters, e.g. "image/jpeg". */
    val contentType: String,
    val fileName: String?,
    val data: ByteArray,
    /** The character set number from the message, 0 when not given. */
    val charset: Int = 0,
) {
    val kind: PartKind get() = PartKinds.of(contentType, fileName)

    /** Words in this part, decoded with the character set it came with. */
    fun asText(): String {
        val raw = Texts.decode(data, charset)
        return if (contentType == "text/html") Texts.stripTags(raw) else raw
    }

    /** A file name safe to save under, with an extension that matches the content. */
    fun safeFileName(index: Int): String = Files.safeName(fileName, contentType, index)
}

/** A downloaded picture or group text. */
class MmsMessage(
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String?,
    val dateSeconds: Long,
    val messageId: String?,
    val transactionId: ByteArray?,
    val mmsVersion: Int,
    val parts: List<MmsPart>,
) {
    /** All the words, in order. */
    val text: String
        get() = parts.filter { it.kind == PartKind.TEXT }
            .joinToString("\n") { it.asText().trim() }
            .trim()

    /** Everything that isn't words or layout: pictures, clips, cards, invites, other files. */
    val attachments: List<MmsPart> get() = parts.filter { it.kind != PartKind.TEXT && it.kind != PartKind.LAYOUT }

    /** Everyone in the conversation except this phone's own numbers. More than one means a group text. */
    fun others(myNumbers: Collection<String>): List<String> =
        Addresses.distinct(listOfNotNull(from) + to + cc).filterNot { a -> myNumbers.any { Addresses.same(a, it) } }
}

/** "You have a picture text waiting": where to fetch it, and who it is from. */
class MmsNotice(
    val transactionId: ByteArray,
    val contentLocation: String,
    val from: String?,
    val subject: String?,
    val sizeBytes: Long,
    /** When the carrier deletes it, in seconds since 1970 (relative times are already added to now). */
    val expiresAtSeconds: Long,
    val mmsVersion: Int,
)

/** The carrier's answer to a picture text we sent. */
class MmsSendResult(val status: Int, val messageId: String?) {
    val ok: Boolean get() = status == PduHeaders.RESPONSE_STATUS_OK

    /** Worth trying again later: the network was busy, not a problem with the message. */
    val worthRetrying: Boolean
        get() = !ok && (status in 0xC0..0xDF || status == PduHeaders.RESPONSE_STATUS_ERROR_NETWORK_PROBLEM ||
            status == PduHeaders.RESPONSE_STATUS_ERROR_UNSPECIFIED)

    /** Plain words for what went wrong, said aloud with "Not sent". */
    val reason: String
        get() = when (status) {
            PduHeaders.RESPONSE_STATUS_OK -> "Sent"
            PduHeaders.RESPONSE_STATUS_ERROR_CONTENT_NOT_ACCEPTED,
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_CONTENT_NOT_ACCEPTED ->
                "The carrier would not take it. It may be too big."
            PduHeaders.RESPONSE_STATUS_ERROR_SENDING_ADDRESS_UNRESOLVED,
            PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_SENDNG_ADDRESS_UNRESOLVED,
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SENDING_ADDRESS_UNRESOLVED ->
                "One of the numbers could not be reached."
            PduHeaders.RESPONSE_STATUS_ERROR_SERVICE_DENIED,
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SERVICE_DENIED ->
                "The carrier turned it down. Picture texts may be off on this plan."
            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_LACK_OF_PREPAID -> "Not enough credit on the plan."
            else -> if (worthRetrying) "The network was busy." else "The carrier could not send it."
        }
}

/** The carrier saying a picture text we sent reached (or didn't reach) someone. */
class MmsDelivery(val messageId: String?, val to: List<String>, val status: Int) {
    val delivered: Boolean get() = status == PduHeaders.STATUS_RETRIEVED || status == PduHeaders.STATUS_FORWARDED
    val failed: Boolean
        get() = status == PduHeaders.STATUS_EXPIRED || status == PduHeaders.STATUS_REJECTED ||
            status == PduHeaders.STATUS_UNREACHABLE || status == PduHeaders.STATUS_UNRECOGNIZED
}

/** Something to send with a picture text. */
class Attachment(val contentType: String, val fileName: String?, val data: ByteArray)

/** Reads and writes the picture-text (MMS) message format. */
object MmsCodec {
    const val VERSION_1_2 = PduHeaders.MMS_VERSION_1_2
    const val STATUS_RETRIEVED = PduHeaders.STATUS_RETRIEVED
    const val STATUS_DEFERRED = PduHeaders.STATUS_DEFERRED
    const val STATUS_REJECTED = PduHeaders.STATUS_REJECTED
    const val STATUS_UNRECOGNIZED = PduHeaders.STATUS_UNRECOGNIZED

    /** Seven days, the usual time a carrier keeps a picture text for someone. */
    private const val EXPIRY_SECONDS = 7L * 24 * 60 * 60

    /** Reads a "picture text waiting" push. Null if it is anything else, or broken. */
    fun readNotice(bytes: ByteArray): MmsNotice? {
        val pdu = parse(bytes) as? NotificationInd ?: return null
        val location = pdu.contentLocation?.let { String(it, Charsets.ISO_8859_1) } ?: return null
        val tid = pdu.transactionId ?: return null
        return MmsNotice(
            transactionId = tid,
            contentLocation = location,
            from = pdu.from?.let { Addresses.clean(it.string) },
            subject = pdu.subject?.string?.takeIf { it.isNotBlank() },
            sizeBytes = pdu.messageSize,
            expiresAtSeconds = pdu.expiry,
            mmsVersion = pdu.mmsVersion,
        )
    }

    /** Reads a downloaded picture text. Null if it can't be read. */
    fun readMessage(bytes: ByteArray): MmsMessage? {
        val pdu = parse(bytes) as? RetrieveConf ?: return null
        val body = pdu.body ?: PduBody()
        val parts = (0 until body.partsNum).mapNotNull { i -> body.getPart(i)?.let(::toPart) }
        return MmsMessage(
            from = pdu.from?.let { Addresses.clean(it.string) },
            to = addresses(pdu.to),
            cc = addresses(pdu.cc),
            subject = pdu.subject?.string?.takeIf { it.isNotBlank() },
            dateSeconds = pdu.date,
            messageId = pdu.messageId?.let { String(it, Charsets.ISO_8859_1) },
            transactionId = pdu.transactionId,
            mmsVersion = pdu.mmsVersion,
            parts = parts,
        )
    }

    /** Reads the carrier's reply to a send. Null if it is anything else. */
    fun readSendResult(bytes: ByteArray): MmsSendResult? {
        val pdu = parse(bytes) as? SendConf ?: return null
        return MmsSendResult(pdu.responseStatus, pdu.messageId?.let { String(it, Charsets.ISO_8859_1) })
    }

    /** Reads a delivery report. Null if it is anything else. */
    fun readDelivery(bytes: ByteArray): MmsDelivery? {
        val pdu = parse(bytes) as? DeliveryInd ?: return null
        return MmsDelivery(pdu.messageId?.let { String(it, Charsets.ISO_8859_1) }, addresses(pdu.to), pdu.status)
    }

    /** The message type number of a push, or -1. Lets the receiver tell pushes apart cheaply. */
    fun messageType(bytes: ByteArray): Int = parse(bytes)?.messageType ?: -1

    /** Tells the carrier what happened to a picture text it said was waiting. */
    fun notifyResponse(transactionId: ByteArray, mmsVersion: Int, status: Int): ByteArray? = runCatching {
        PduComposer(NotifyRespInd(version(mmsVersion), transactionId, status)).make()
    }.getOrNull()

    /** Tells the carrier a picture text fetched later was received. */
    fun acknowledge(transactionId: ByteArray, mmsVersion: Int): ByteArray? = runCatching {
        PduComposer(AcknowledgeInd(version(mmsVersion), transactionId)).make()
    }.getOrNull()

    /**
     * Builds a picture or group text. Everyone in [recipients] gets it, and replies go to the
     * whole group. Returns null if there is nothing to send or the message can't be built.
     */
    fun buildSend(
        recipients: List<String>,
        text: String?,
        attachments: List<Attachment>,
        subject: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        deliveryReport: Boolean = false,
    ): ByteArray? {
        val to = Addresses.distinct(recipients.map(Addresses::forSending).filter { it.isNotEmpty() })
        val words = text?.trim().orEmpty()
        if (to.isEmpty() || (words.isEmpty() && attachments.isEmpty())) return null

        val req = SendReq()
        req.setTo(to.map { EncodedStringValue(it) }.toTypedArray())
        if (!subject.isNullOrBlank()) req.subject = EncodedStringValue(CharacterSets.UTF_8, subject.toByteArray(Charsets.UTF_8))
        req.date = nowSeconds
        req.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
        req.expiry = EXPIRY_SECONDS
        req.priority = PduHeaders.PRIORITY_NORMAL
        req.deliveryReport = if (deliveryReport) PduHeaders.VALUE_YES else PduHeaders.VALUE_NO
        req.readReport = PduHeaders.VALUE_NO

        val pieces = mutableListOf<Piece>()
        val names = mutableSetOf<String>()
        attachments.forEachIndexed { i, a ->
            val type = a.contentType.lowercase().substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val name = unique(Files.safeName(a.fileName, type, i), names)
            pieces += Piece(type, name, a.data, charset = 0)
        }
        if (words.isNotEmpty()) {
            pieces += Piece("text/plain", unique("text.txt", names), words.toByteArray(Charsets.UTF_8), CharacterSets.UTF_8)
        }

        val body = PduBody()
        body.addPart(part(Piece("application/smil", "smil.xml", Smil.layout(pieces).toByteArray(Charsets.UTF_8), CharacterSets.UTF_8), id = "smil"))
        pieces.forEach { body.addPart(part(it, id = it.name)) }
        req.body = body
        req.messageSize = pieces.sumOf { it.data.size.toLong() }

        return runCatching { PduComposer(req).make() }.getOrNull()
    }

    // ---- inside ----

    internal class Piece(val type: String, val name: String, val data: ByteArray, val charset: Int) {
        val kind: PartKind get() = PartKinds.of(type, name)
    }

    private fun part(p: Piece, id: String): PduPart = PduPart().apply {
        contentType = p.type.toByteArray()
        if (p.charset != 0) charset = p.charset
        name = p.name.toByteArray()
        filename = p.name.toByteArray()
        contentLocation = p.name.toByteArray()
        contentId = "<$id>".toByteArray()
        data = p.data
    }

    private fun unique(name: String, used: MutableSet<String>): String {
        var n = name
        var i = 1
        while (!used.add(n.lowercase())) {
            val dot = name.lastIndexOf('.')
            n = if (dot > 0) "${name.substring(0, dot)}_$i${name.substring(dot)}" else "${name}_$i"
            i++
        }
        return n
    }

    private fun parse(bytes: ByteArray) = runCatching { PduParser(bytes, true).parse() }.getOrNull()

    private fun version(v: Int) = if (v in 0x10..0x7F) v else PduHeaders.CURRENT_MMS_VERSION

    private fun addresses(values: Array<EncodedStringValue>?): List<String> =
        values.orEmpty().mapNotNull { v -> Addresses.clean(v.string).takeIf { it.isNotEmpty() } }

    private fun toPart(p: PduPart): MmsPart? {
        val data = p.data ?: return null
        val type = p.contentType?.let { String(it, Charsets.ISO_8859_1) }?.lowercase()?.substringBefore(';')?.trim()
            ?: "application/octet-stream"
        val name = listOf(p.filename, p.name, p.contentLocation)
            .firstOrNull { it != null && it.isNotEmpty() }
            ?.let { String(it, Charsets.UTF_8) }
        return MmsPart(type, name, data, p.charset)
    }
}

/** Phone numbers and email addresses as they appear in picture texts. */
object Addresses {
    /** "+19195550100/TYPE=PLMN" → "+19195550100". */
    fun clean(raw: String?): String {
        val s = raw?.trim().orEmpty()
        val slash = s.indexOf("/TYPE=", ignoreCase = true)
        return (if (slash >= 0) s.substring(0, slash) else s).trim()
    }

    /** What goes in the To field: digits and a leading +, or an email address as it is. */
    fun forSending(raw: String): String {
        val s = clean(raw)
        if (s.contains('@')) return s
        val digits = s.filter { it.isDigit() }
        return if (s.startsWith("+")) "+$digits" else digits
    }

    /** Same person? Phone numbers match on their last ten digits, so +1 919... equals 919... */
    fun same(a: String, b: String): Boolean {
        val x = clean(a); val y = clean(b)
        if (x.contains('@') || y.contains('@')) return x.equals(y, ignoreCase = true)
        val dx = x.filter { it.isDigit() }; val dy = y.filter { it.isDigit() }
        if (dx.isEmpty() || dy.isEmpty()) return x.equals(y, ignoreCase = true)
        if (dx.length >= 10 && dy.length >= 10) return dx.takeLast(10) == dy.takeLast(10)
        return dx == dy
    }

    /** Drops repeats (the same number written two ways), keeping the first spelling. */
    fun distinct(list: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (a in list) {
            val c = clean(a)
            if (c.isNotEmpty() && out.none { same(it, c) }) out += c
        }
        return out
    }
}

/** Decides what a part is from its type, or from its file name when the type is vague. */
object PartKinds {
    private val byExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "mov" to "video/quicktime",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "amr" to "audio/amr", "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg", "wav" to "audio/wav", "opus" to "audio/ogg",
        "vcf" to "text/x-vcard", "vcs" to "text/x-vcalendar", "ics" to "text/calendar",
        "txt" to "text/plain", "smil" to "application/smil",
    )

    /** The type to use: the one given, or a better guess from the file name when it says "octet-stream". */
    fun typeOf(contentType: String, fileName: String?): String {
        val t = contentType.lowercase().substringBefore(';').trim()
        if (t.isNotEmpty() && t != "application/octet-stream" && t != "application/unknown") return t
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return byExtension[ext] ?: t.ifEmpty { "application/octet-stream" }
    }

    fun of(contentType: String, fileName: String?): PartKind {
        val t = typeOf(contentType, fileName)
        return when {
            t == "text/plain" || t == "text/html" -> PartKind.TEXT
            t == "application/smil" -> PartKind.LAYOUT
            t == "text/x-vcard" || t == "text/vcard" || t == "text/directory" -> PartKind.CONTACT
            t == "text/x-vcalendar" || t == "text/calendar" -> PartKind.EVENT
            t.startsWith("image/") -> PartKind.IMAGE
            t.startsWith("audio/") || t == "application/ogg" -> PartKind.AUDIO
            t.startsWith("video/") -> PartKind.VIDEO
            else -> PartKind.OTHER
        }
    }

    /** Words for a part, said when it has nothing else to say. */
    fun spokenName(kind: PartKind): String = when (kind) {
        PartKind.TEXT -> "Words"
        PartKind.IMAGE -> "Picture"
        PartKind.AUDIO -> "Voice or sound clip"
        PartKind.VIDEO -> "Video"
        PartKind.CONTACT -> "Contact card"
        PartKind.EVENT -> "Calendar invite"
        PartKind.LAYOUT -> "Layout"
        PartKind.OTHER -> "File"
    }
}

internal object Texts {
    fun decode(data: ByteArray, charset: Int): String {
        val name = if (charset == 0) null else runCatching { CharacterSets.getMimeName(charset) }.getOrNull()
        val cs = name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(data, cs).removePrefix("﻿")
    }

    fun stripTags(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .trim()
}

internal object Files {
    private val extensionFor = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png", "image/gif" to "gif",
        "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif", "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp", "image/vnd.wap.wbmp" to "wbmp",
        "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/3gpp2" to "3g2", "video/quicktime" to "mov",
        "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/amr" to "amr", "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3", "audio/ogg" to "ogg", "application/ogg" to "ogg", "audio/3gpp" to "3gp",
        "audio/wav" to "wav", "audio/x-wav" to "wav",
        "text/x-vcard" to "vcf", "text/vcard" to "vcf", "text/x-vcalendar" to "vcs", "text/calendar" to "ics",
        "text/plain" to "txt", "application/smil" to "smil", "application/pdf" to "pdf",
    )

    /** Keeps letters, digits, dots, dashes and underscores, and makes sure there's an extension. */
    fun safeName(given: String?, contentType: String, index: Int): String {
        val base = given?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.trim('<', '>', '"').orEmpty()
        var name = base.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }
            .joinToString("").trim('.', '_').take(60)
        val ext = extensionFor[contentType.lowercase()]
        if (name.isEmpty()) name = "part$index"
        if (ext != null && !name.contains('.')) name = "$name.$ext"
        return name
    }
}

/** A plain SMIL layout: one slide per piece. Some phones show nothing without it. */
internal object Smil {
    fun layout(pieces: List<MmsCodec.Piece>): String = buildString {
        append("<smil><head><layout><root-layout/>")
        append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
        append("</layout></head><body>")
        for (p in pieces) {
            val src = escape(p.name)
            val tag = when (p.kind) {
                PartKind.TEXT -> "<text src=\"$src\" region=\"Text\"/>"
                PartKind.IMAGE -> "<img src=\"$src\" region=\"Image\"/>"
                PartKind.AUDIO -> "<audio src=\"$src\"/>"
                PartKind.VIDEO -> "<video src=\"$src\" region=\"Image\"/>"
                else -> "<ref src=\"$src\"/>"
            }
            append("<par dur=\"5000ms\">").append(tag).append("</par>")
        }
        append("</body></smil>")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
