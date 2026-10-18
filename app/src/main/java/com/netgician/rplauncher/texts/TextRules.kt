package com.netgician.rplauncher.texts

import com.netgician.rplauncher.i18n.tr
import android.app.Activity
import android.telephony.SmsManager
import com.netgician.rplauncher.mms.PartKind
import java.util.Calendar
import java.util.Locale

/**
 * The rules for texts that don't need a phone: what is said aloud, what failures mean in
 * plain words, how names are joined. Kept apart so the tests can check every one.
 */

/** Where a message is. */
enum class Box {
    /** Received. */
    INBOX,
    /** Sent and accepted by the carrier. */
    SENT,
    /** On its way out. */
    SENDING,
    /** Not sent. Stays marked until it goes through. */
    FAILED,
    /** A picture text the carrier is holding: it still needs downloading. */
    WAITING,
    DRAFT,
}

/** A picture text that is waiting at the carrier, and how its download is going. */
enum class DownloadState { NOT_STARTED, DOWNLOADING, RETRYING, FAILED }

/** One attachment in a picture text, as stored on the phone. */
data class TextPart(
    val id: Long,
    val kind: PartKind,
    val contentType: String,
    val name: String?,
    /** content://mms/part/<id> */
    val uri: String,
)

/** One text in a conversation, oldest to newest. */
data class TextMessage(
    val id: Long,
    val isMms: Boolean,
    val box: Box,
    /** Milliseconds since 1970. */
    val date: Long,
    val body: String,
    /** Who sent it; null when this phone sent it. */
    val from: String?,
    val parts: List<TextPart> = emptyList(),
    val delivered: Boolean = false,
    /** Plain words for why it wasn't sent or downloaded, when known. */
    val problem: String? = null,
    val download: DownloadState = DownloadState.NOT_STARTED,
    val subject: String? = null,
    val read: Boolean = true,
) {
    val key: String get() = (if (isMms) "mms:" else "sms:") + id
    val outgoing: Boolean get() = from == null
}

/** One conversation: a person, or a group. */
data class Conversation(
    val threadId: Long,
    /** Everyone in it except this phone. */
    val addresses: List<String>,
    /** Contact names where known, else the numbers, in the same order. */
    val names: List<String>,
    val snippet: String,
    val date: Long,
    val unread: Int,
    val hasProblem: Boolean,
) {
    val isGroup: Boolean get() = addresses.size > 1
    val title: String get() = People.join(names)
}

object People {
    /** "Mary", "Mary and Bob", "Mary, Bob and Ana", "Mary, Bob and 3 others". */
    fun join(names: List<String>, max: Int = 3): String = when {
        names.isEmpty() -> tr("Unknown")
        names.size == 1 -> names[0]
        names.size <= max -> names.dropLast(1).joinToString(", ") + tr(" and ") + names.last()
        else -> names.take(max - 1).joinToString(", ") + tr(" and %s others", names.size - (max - 1))
    }

    /** A number said digit by digit: "9 1 9, 5 5 5, 0 1 0 0". Names are left alone. */
    fun spoken(nameOrNumber: String): String =
        if (nameOrNumber.any { it.isLetter() }) nameOrNumber
        else com.netgician.rplauncher.mms.ContactCard.spacedNumber(nameOrNumber).ifBlank { nameOrNumber }
}

/** What a failure means, in words a person can act on. */
object Problems {
    fun sms(resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> tr("Sent")
        SmsManager.RESULT_ERROR_NO_SERVICE -> tr("No signal.")
        SmsManager.RESULT_ERROR_RADIO_OFF -> tr("The phone's radio is off. Check that airplane mode is off.")
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> tr("Too many texts at once. Wait a minute and try again.")
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> tr("Texts to this short number are blocked on this phone.")
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> tr("This number isn't on the phone's allowed list.")
        else -> tr("The carrier could not send it.")
    }

    fun mms(resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> tr("Sent")
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> tr("Picture texts need mobile data. Check that mobile data is on.")
        SmsManager.MMS_ERROR_INVALID_APN,
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> tr("This phone's picture text settings for the carrier are missing.")
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
        SmsManager.MMS_ERROR_HTTP_FAILURE,
        SmsManager.MMS_ERROR_IO_ERROR,
        SmsManager.MMS_ERROR_RETRY -> tr("Couldn't reach the carrier. The signal may be weak.")
        else -> tr("The carrier could not send it.")
    }

    /** Worth trying a download again by itself: the network, not the message. */
    fun mmsWorthRetrying(resultCode: Int): Boolean = resultCode != SmsManager.MMS_ERROR_INVALID_APN &&
        resultCode != SmsManager.MMS_ERROR_CONFIGURATION_ERROR

    val TOO_BIG: String get() = tr("It's too big for a picture text.")
    val UNREADABLE: String get() = tr("It arrived damaged and can't be shown.")
    val GONE: String get() = tr("The carrier no longer has it.")
}

/** Picture texts that don't download are tried again by themselves: after 1, 5 and 30 minutes. */
object Retry {
    val delaysMinutes = listOf(1L, 5L, 30L)

    /** Minutes before the next try, or null when it's time to tell the person. */
    fun nextDelay(attemptsSoFar: Int): Long? = delaysMinutes.getOrNull(attemptsSoFar - 1)

    /** A download that has been "downloading" this long has been lost (the phone restarted, say). */
    const val STUCK_MS = 10 * 60_000L
}

/** What is said for each message, and how the times are put. */
object TextSpeech {
    /** "Today at 3:05 PM", "Yesterday at 9:12 AM", "Monday at 8:00 PM", "March 3 at 10:00 AM". */
    fun whenSaid(date: Long, now: Long, locale: Locale = Locale.getDefault(), time: (Long) -> String): String {
        val then = Calendar.getInstance().apply { timeInMillis = date }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = daysBetween(then, today)
        val es = locale.language == "es"
        val day = when {
            days == 0 -> if (es) "Hoy" else "Today"
            days == 1 -> if (es) "Ayer" else "Yesterday"
            days in 2..6 -> then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale)
                ?.replaceFirstChar { it.titlecase(locale) } ?: ""
            else -> {
                val month = then.getDisplayName(Calendar.MONTH, Calendar.LONG, locale)
                    ?.replaceFirstChar { it.titlecase(locale) } ?: ""
                val d = then.get(Calendar.DAY_OF_MONTH)
                val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                val base = if (es) "$d de $month" else "$month $d"
                if (sameYear) base else "$base, ${then.get(Calendar.YEAR)}"
            }
        }
        return "$day ${if (es) "a las" else "at"} ${time(date)}"
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun dayNumber(c: Calendar): Long {
            val copy = c.clone() as Calendar
            copy.set(Calendar.HOUR_OF_DAY, 12); copy.set(Calendar.MINUTE, 0)
            copy.set(Calendar.SECOND, 0); copy.set(Calendar.MILLISECOND, 0)
            return (copy.timeInMillis + copy.get(Calendar.ZONE_OFFSET) + copy.get(Calendar.DST_OFFSET)) / 86_400_000L
        }
        return (dayNumber(b) - dayNumber(a)).toInt()
    }

    /** What the attachments are, in a few words: "A picture and a voice clip." */
    fun attachments(parts: List<TextPart>): String {
        if (parts.isEmpty()) return ""
        val counts = parts.groupingBy { it.kind }.eachCount()
        val bits = counts.map { (kind, n) ->
            val (one, many) = when (kind) {
                PartKind.IMAGE -> "a picture" to "%s pictures"
                PartKind.AUDIO -> "a voice or sound clip" to "%s voice or sound clips"
                PartKind.VIDEO -> "a video" to "%s videos"
                PartKind.CONTACT -> "a contact card" to "%s contact cards"
                PartKind.EVENT -> "a calendar invite" to "%s calendar invites"
                else -> "a file" to "%s files"
            }
            if (n == 1) tr(one) else tr(many, n)
        }
        val joined = if (bits.size == 1) bits[0] else bits.dropLast(1).joinToString(", ") + tr(" and ") + bits.last()
        return joined.replaceFirstChar { it.uppercase() } + "."
    }

    /**
     * Everything said for one message, in order: who, when, the words, what's attached,
     * then how sending or downloading went.
     */
    /** A message to be spoken in three parts, so the words can be read in their own language's voice. */
    data class Spoken(val head: String, val body: String, val tail: String) {
        val all: String get() = listOf(head, body, tail).filter { it.isNotBlank() }.let { bits ->
            bits.mapIndexed { i, b -> if (i < bits.lastIndex && b.last() !in ".!?…:;") "$b." else b }
        }.joinToString(" ")
    }

    /**
     * Everything said for one message, in order: who and when; the words; what's attached and
     * how sending or downloading went.
     */
    fun spoken(m: TextMessage, fromName: String?, group: Boolean, whenText: String): Spoken {
        val head = buildList {
            add(
                when {
                    m.outgoing -> tr("You sent, %s.", whenText)
                    group -> tr("From %s, in the group, %s.", fromName ?: tr("someone"), whenText)
                    else -> tr("From %s, %s.", fromName ?: tr("someone"), whenText)
                },
            )
            m.subject?.takeIf { it.isNotBlank() }?.let { add(tr("Subject: %s.", it)) }
        }.joinToString(" ")
        val body = if (m.box == Box.WAITING) "" else m.body.trim()
        val tail = buildList {
            if (m.box == Box.WAITING) {
                add(
                    when (m.download) {
                        DownloadState.FAILED -> tr("A picture text that didn't download. %s Double tap Try again.", m.problem ?: "").replace("  ", " ")
                        DownloadState.RETRYING -> tr("A picture text. It didn't download yet; trying again soon.")
                        DownloadState.NOT_STARTED -> tr("A picture text waiting at the carrier. Double tap Download.")
                        DownloadState.DOWNLOADING -> tr("A picture text, downloading.")
                    },
                )
            } else {
                attachments(m.parts).takeIf { it.isNotEmpty() }?.let { add(it) }
                if (m.body.isBlank() && m.parts.isEmpty()) add(tr("No words."))
            }
            when {
                m.box == Box.FAILED -> add(tr("Not sent. %s Double tap Try again.", m.problem ?: "").replace("  ", " "))
                m.box == Box.SENDING -> add(tr("Sending."))
                m.outgoing && m.delivered -> add(tr("Delivered."))
            }
        }.joinToString(" ")
        return Spoken(head, body, tail)
    }

    fun message(m: TextMessage, fromName: String?, group: Boolean, whenText: String): String =
        spoken(m, fromName, group, whenText).all

    /** How a conversation is read in the list: "Mary. 2 new. Running late." */
    fun conversation(c: Conversation): String = buildList {
        add(c.names.joinToString(", ").let { if (c.isGroup) tr("Group: %s.", it) else "$it." })
        if (c.unread > 0) add(if (c.unread == 1) tr("1 new.") else tr("%s new.", c.unread))
        if (c.hasProblem) add(tr("A text was not sent."))
        c.snippet.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
    }.joinToString(" ")
}

/** Short replies a person can send with one double tap. */
object QuickReplies {
    private val english = listOf(
        "On my way", "Yes", "No", "OK", "Running late", "Call me please", "I'll call you later", "Thank you",
    )
    private val spanish = listOf(
        "Voy en camino", "Sí", "No", "Está bien", "Llego tarde", "Llámame por favor", "Te llamo luego", "Gracias",
    )

    fun forLanguage(language: String): List<String> = if (language == "es") spanish else english
}

/** sms:, smsto:, mms: and mmsto: links from other apps: "smsto:5551234;5559876?body=Hi". */
object ComposeLink {
    data class Parsed(val recipients: List<String>, val body: String?)

    /** [schemeSpecific] is the part after the colon, already URL-decoded. */
    fun parse(schemeSpecific: String?): Parsed {
        val s = schemeSpecific.orEmpty().removePrefix("//")
        val q = s.indexOf('?')
        val who = if (q >= 0) s.substring(0, q) else s
        val query = if (q >= 0) s.substring(q + 1) else ""
        val recipients = who.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val body = query.split('&').firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.takeIf { it.isNotEmpty() }
        return Parsed(recipients, body)
    }
}

/** Sizes for squeezing a photo under the carrier's limit, largest first. */
object PhotoFit {
    data class Step(val longSide: Int, val quality: Int)

    val steps = listOf(
        Step(1920, 85), Step(1600, 80), Step(1280, 75), Step(1024, 70), Step(800, 65), Step(640, 60), Step(480, 55),
    )

    /** Room for the photo: the carrier's limit, less space for the words and the message's own headers. */
    fun budget(carrierMaxBytes: Int, otherBytes: Int): Int =
        ((carrierMaxBytes * 0.92).toInt() - otherBytes - 2_048).coerceAtLeast(30_000)
}
