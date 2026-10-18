package com.netgician.rplauncher.calls

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
        fun unit(n: Long, one: String) = "$n $one" + if (n == 1L) "" else "s"
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { unit(it, "hour") },
            m.takeIf { it > 0 }?.let { unit(it, "minute") },
            sec.takeIf { it > 0 || (h == 0L && m == 0L) }?.let { unit(it, "second") },
        ).joinToString(", ")
    }

    /** The status line under the name. */
    fun status(c: CallInfo, now: Long): String = when (c.phase) {
        CallPhase.RINGING -> "Calling you"
        CallPhase.DIALING, CallPhase.CONNECTING -> "Calling…"
        CallPhase.ACTIVE -> if (c.connectedAt > 0) "On call ${clock((now - c.connectedAt) / 1000)}" else "On call"
        CallPhase.HOLDING -> "On hold"
        CallPhase.ENDED -> "Call ended"
        CallPhase.OTHER -> ""
    }

    /** Said when a call comes in. */
    fun ringing(name: String): String = "Call from $name. Tap Answer, or Decline."

    /** Said when a call ends. */
    fun ended(name: String, seconds: Long): String =
        if (seconds > 0) "Call with $name ended after ${spokenDuration(seconds)}." else "Call with $name ended."

    /** How a recent call is read: "Missed call from Mary, Today at 3:05 PM." */
    fun recent(r: RecentCall, who: String, whenText: String): String = when (r.kind) {
        RecentCall.Kind.MISSED -> "Missed call from $who, $whenText."
        RecentCall.Kind.INCOMING -> "Call from $who, $whenText, ${spokenDuration(r.durationSeconds)}."
        RecentCall.Kind.OUTGOING -> "You called $who, $whenText" + if (r.durationSeconds > 0) ", ${spokenDuration(r.durationSeconds)}." else ", no answer."
        RecentCall.Kind.DECLINED -> "Declined call from $who, $whenText."
        RecentCall.Kind.BLOCKED -> "Blocked call from $who, $whenText."
        RecentCall.Kind.VOICEMAIL -> "Voicemail from $who, $whenText."
    }

    /** The short label on the tile: "Mary · missed". */
    fun recentLabel(r: RecentCall, who: String): String = when (r.kind) {
        RecentCall.Kind.MISSED -> "$who · missed"
        RecentCall.Kind.INCOMING -> "$who · in"
        RecentCall.Kind.OUTGOING -> "$who · out"
        RecentCall.Kind.DECLINED -> "$who · declined"
        RecentCall.Kind.BLOCKED -> "$who · blocked"
        RecentCall.Kind.VOICEMAIL -> "$who · voicemail"
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
            else -> d.chunked(3)
        }
        return (if (plus) "+" else "") + groups.joinToString(" ")
    }
}
