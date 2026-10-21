package com.netgician.rplauncher.model

import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.i18n.tr
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * One alarm or reminder. A reminder has a [message], said aloud when it goes off ("Take your
 * pills"); an alarm has none. Kept apart from Android so the tests can check every rule.
 */
data class Alarm(
    val id: Long,
    /** 0 to 23. */
    val hour: Int,
    val minute: Int,
    /** Days it repeats, Monday 1 to Sunday 7. Empty: just once. */
    val days: Set<Int> = emptySet(),
    val message: String? = null,
    val on: Boolean = true,
    /** Snoozed ("10 more minutes") until this time, or 0. */
    val snoozedUntil: Long = 0L,
) {
    val isReminder: Boolean get() = !message.isNullOrBlank()
}

object AlarmRules {
    const val SNOOZE_MS = 10 * 60_000L

    /** A ringing alarm goes quiet by itself after this long. */
    const val RING_FOR_MS = 10 * 60_000L

    val EVERY_DAY: Set<Int> = (1..7).toSet()
    val WEEKDAYS: Set<Int> = (1..5).toSet()
    val WEEKEND: Set<Int> = setOf(6, 7)

    /** The next time it rings after [now]: the snooze if there is one, else the next chosen day at its time. */
    fun next(a: Alarm, now: Long, zone: ZoneId): Long? {
        if (!a.on) return null
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        var regular: Long? = null
        for (i in 0..7) {
            val day = start.plusDays(i.toLong())
            if (a.days.isNotEmpty() && day.dayOfWeek.value !in a.days) continue
            val at = day.atTime(a.hour, a.minute).atZone(zone).toInstant().toEpochMilli()
            if (at > now) {
                regular = at
                break
            }
        }
        val snooze = a.snoozedUntil.takeIf { it > now }
        return listOfNotNull(regular, snooze).minOrNull()
    }

    /** After it rings: a one-time alarm turns off; a repeating one stays on. Any snooze is used up. */
    fun afterRinging(a: Alarm): Alarm = a.copy(on = a.days.isNotEmpty(), snoozedUntil = 0L)

    fun snoozed(a: Alarm, now: Long): Alarm = a.copy(on = true, snoozedUntil = now + SNOOZE_MS)

    /** The most digits a typed time can have: two for the hour, two for the minutes. */
    const val MAX_DIGITS = 4

    /** The hour box of a typed time: everything before the last two digits ("730" is 7). */
    fun typedHour(digits: String): String = digits.dropLast(2).trimStart('0').ifEmpty { "0" }

    /** The minutes box of a typed time: the last two digits ("730" is 30, "5" is 05). */
    fun typedMinute(digits: String): String = digits.takeLast(2).padStart(2, '0')

    /** A typed time as hour and minute: "730" is 7:30, "1945" is 19:45. Null if it isn't a time of day. */
    fun typed(digits: String): Pair<Int, Int>? {
        if (digits.length < 3 || digits.length > MAX_DIGITS || digits.any { !it.isDigit() }) return null
        val h = typedHour(digits).toInt()
        val m = typedMinute(digits).toInt()
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    /** On a 12-hour phone, 1 to 12 o'clock could be morning or evening, so it's asked. */
    fun needsAmPm(hour: Int, is24: Boolean): Boolean = !is24 && hour in 1..12

    fun withAmPm(hour: Int, pm: Boolean): Int = when {
        pm && hour < 12 -> hour + 12
        !pm && hour == 12 -> 0
        else -> hour
    }
}

/** What alarms and reminders show and say. */
object AlarmWords {
    /** "7:30 AM", or "19:30" on a 24-hour phone. */
    fun time(hour: Int, minute: Int, is24: Boolean, locale: Locale): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm a", locale))

    fun dayName(day: Int, locale: Locale, short: Boolean = false): String =
        DayOfWeek.of(day).getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale)
            .replaceFirstChar { it.titlecase(locale) }

    /** "Once", "Every day", "Weekdays", "Weekends", or the days: "Mon, Wed, Fri". */
    fun days(days: Set<Int>, locale: Locale, short: Boolean = true): String = when (days) {
        emptySet<Int>() -> tr("Once")
        AlarmRules.EVERY_DAY -> tr("Every day")
        AlarmRules.WEEKDAYS -> tr("Weekdays")
        AlarmRules.WEEKEND -> tr("Weekends")
        else -> days.sorted().joinToString(", ") { dayName(it, locale, short) }
    }

    /** The list row: "7:30 AM · Weekdays", "8:00 AM · Take your pills", with "(off)" when off. */
    fun row(a: Alarm, is24: Boolean, locale: Locale): String {
        val what = if (a.isReminder) a.message!! else days(a.days, locale)
        val off = if (a.on) "" else " " + tr("(off)")
        return "${time(a.hour, a.minute, is24, locale)} · $what$off"
    }

    /** The same, for the voice, with the days in full. */
    fun rowSpoken(a: Alarm, is24: Boolean, locale: Locale): String {
        val t = time(a.hour, a.minute, is24, locale)
        val d = days(a.days, locale, short = false)
        val s = if (a.isReminder) tr("Reminder at %s, %s: %s", t, d, a.message!!) else tr("Alarm at %s, %s", t, d)
        return if (a.on) s else tr("%s. Turned off.", s)
    }

    /** "in 7 hours, 12 minutes", rounded up to the minute. */
    fun inHowLong(at: Long, now: Long): String {
        val seconds = ((at - now).coerceAtLeast(0L) + 59_999L) / 60_000L * 60L
        return CallWords.spokenDuration(seconds)
    }

    /** Said when one is saved: what, when, how often, and how long until it rings. */
    fun saved(a: Alarm, is24: Boolean, locale: Locale, next: Long?, now: Long): String {
        val t = time(a.hour, a.minute, is24, locale)
        val d = days(a.days, locale, short = false)
        val head = if (a.isReminder) tr("Reminder set: %s, at %s, %s.", a.message!!, t, d) else tr("Alarm set for %s, %s.", t, d)
        return if (next == null) head else "$head " + tr("It rings in %s.", inHowLong(next, now))
    }

    /** Said again and again while it rings. */
    fun ringing(a: Alarm, is24: Boolean, locale: Locale): String =
        if (a.isReminder) tr("Reminder: %s", a.message!!) else tr("Alarm. It's %s.", time(a.hour, a.minute, is24, locale))
}
