package com.netgician.rplauncher.calls

import com.netgician.rplauncher.i18n.tr

/** What a call is doing, in the words the call screen shows and says. */
enum class CallPhase { RINGING, DIALING, CONNECTING, ACTIVE, HOLDING, ENDED, OTHER }

/** One call on the call screen. */
data class CallInfo(
    val id: String,
    val number: String,
    val name: String,
    val phase: CallPhase,
    /** When it connected, in ms, or 0 before then. */
    val connectedAt: Long,
    val conference: Boolean = false,
)

/** A line in Recent calls. */
data class RecentCall(
    val number: String,
    val name: String?,
    val kind: Kind,
    val date: Long,
    val durationSeconds: Long,
    val isNew: Boolean,
) {
    enum class Kind { INCOMING, OUTGOING, MISSED, DECLINED, BLOCKED, VOICEMAIL }
}

/** The words for calls, kept apart from Android so the tests can check them. */
object CallWords {
    /** "2:05" for the screen. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "2 minutes, 5 seconds" for the voice. */
    fun spokenDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        fun unit(n: Long, one: String, many: String) = if (n == 1L) tr(one) else tr(many, n)
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { unit(it, "1 hour", "%s hours") },
            m.takeIf { it > 0 }?.let { unit(it, "1 minute", "%s minutes") },
            sec.takeIf { it > 0 || (h == 0L && m == 0L) }?.let { unit(it, "1 second", "%s seconds") },
        ).joinToString(", ")
    }

    /** The status line under the name. */
    fun status(c: CallInfo, now: Long): String = when (c.phase) {
        CallPhase.RINGING -> tr("Calling you")
        CallPhase.DIALING, CallPhase.CONNECTING -> tr("Calling…")
        CallPhase.ACTIVE -> if (c.connectedAt > 0) tr("On call %s", clock((now - c.connectedAt) / 1000)) else tr("On call")
        CallPhase.HOLDING -> tr("On hold")
        CallPhase.ENDED -> tr("Call ended")
        CallPhase.OTHER -> ""
    }

    /** Said when a call comes in. */
    fun ringing(name: String): String = tr("Call from %s. Tap Answer, or Decline.", name)

    /** Said when a call ends. */
    fun ended(name: String, seconds: Long): String =
        if (seconds > 0) tr("Call with %s ended after %s.", name, spokenDuration(seconds)) else tr("Call with %s ended.", name)

    /** How a recent call is read: "Missed call from Mary, Today at 3:05 PM." */
    fun recent(r: RecentCall, who: String, whenText: String): String = when (r.kind) {
        RecentCall.Kind.MISSED -> tr("Missed call from %s, %s.", who, whenText)
        RecentCall.Kind.INCOMING -> tr("Call from %s, %s, %s.", who, whenText, spokenDuration(r.durationSeconds))
        RecentCall.Kind.OUTGOING -> tr("You called %s, %s", who, whenText) + if (r.durationSeconds > 0) ", ${spokenDuration(r.durationSeconds)}." else tr(", no answer.")
        RecentCall.Kind.DECLINED -> tr("Declined call from %s, %s.", who, whenText)
        RecentCall.Kind.BLOCKED -> tr("Blocked call from %s, %s.", who, whenText)
        RecentCall.Kind.VOICEMAIL -> tr("Voicemail from %s, %s.", who, whenText)
    }

    /** The short label on the tile: "Mary · missed". */
    fun recentLabel(r: RecentCall, who: String): String = when (r.kind) {
        RecentCall.Kind.MISSED -> tr("%s · missed", who)
        RecentCall.Kind.INCOMING -> tr("%s · in", who)
        RecentCall.Kind.OUTGOING -> tr("%s · out", who)
        RecentCall.Kind.DECLINED -> tr("%s · declined", who)
        RecentCall.Kind.BLOCKED -> tr("%s · blocked", who)
        RecentCall.Kind.VOICEMAIL -> tr("%s · voicemail", who)
    }

    /** Each keypad key, said as pressed. */
    fun key(k: String): String = when (k) {
        "*" -> "star"
        "#" -> "pound"
        "+" -> "plus"
        else -> k
    }

    /** The typed number, grouped for reading on screen: "919 555 0100". */
    fun grouped(number: String): String {
        val plus = number.startsWith("+")
        val d = number.removePrefix("+")
        if (d.any { it == '*' || it == '#' } || d.length < 4) return number
        val groups = when {
            d.length == 10 -> listOf(d.substring(0, 3), d.substring(3, 6), d.substring(6))
            d.length == 11 && d.startsWith("1") -> listOf("1", d.substring(1, 4), d.substring(4, 7), d.substring(7))
            d.length == 7 -> listOf(d.substring(0, 3), d.substring(3))
            plus && d.length == 11 -> listOf(d.substring(0, 2), d.substring(2, 5), d.substring(5, 8), d.substring(8))
            else -> d.chunked(3)
        }
        return (if (plus) "+" else "") + groups.joinToString(" ")
    }
}
