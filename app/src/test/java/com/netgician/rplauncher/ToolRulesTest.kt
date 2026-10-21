package com.netgician.rplauncher

import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.model.Alarm
import com.netgician.rplauncher.model.AlarmRules
import com.netgician.rplauncher.model.AlarmWords
import com.netgician.rplauncher.model.BatteryRules
import com.netgician.rplauncher.model.Calc
import com.netgician.rplauncher.model.CalcRules
import com.netgician.rplauncher.model.LightRules
import com.netgician.rplauncher.system.Alarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Alarms and reminders, the calculator, the battery warning and the light finder. */
class ToolRulesTest {
    private val zone = ZoneId.of("America/New_York")

    /** Wednesday 21 October 2026 at the given time. */
    private fun wed(h: Int, m: Int): Long = LocalDateTime.of(2026, 10, 21, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun at(day: Int, h: Int, m: Int): Long = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @After
    fun english() = Lang.choose("en")

    @Test
    fun nextTime() {
        val once = Alarm(1, 7, 30)
        assertEquals(at(22, 7, 30), AlarmRules.next(once, wed(9, 0), zone))
        assertEquals(at(21, 7, 30), AlarmRules.next(once, wed(6, 0), zone))
        // Weekdays, set on a Friday evening: rings Monday.
        val weekdays = Alarm(2, 7, 30, days = AlarmRules.WEEKDAYS)
        assertEquals(at(26, 7, 30), AlarmRules.next(weekdays, at(23, 20, 0), zone))
        // Saturday only, from Wednesday.
        assertEquals(at(24, 9, 0), AlarmRules.next(Alarm(3, 9, 0, days = setOf(6)), wed(10, 0), zone))
        assertNull(AlarmRules.next(once.copy(on = false), wed(6, 0), zone))
    }

    @Test
    fun ringingAndSnooze() {
        val once = Alarm(1, 7, 30)
        assertFalse(AlarmRules.afterRinging(once).on)
        assertTrue(AlarmRules.afterRinging(once.copy(days = AlarmRules.EVERY_DAY)).on)
        val now = wed(7, 30)
        val snoozed = AlarmRules.snoozed(AlarmRules.afterRinging(once), now)
        assertTrue(snoozed.on)
        assertEquals(now + AlarmRules.SNOOZE_MS, AlarmRules.next(snoozed, now, zone))
        // A daily alarm snoozed: the snooze comes first.
        val daily = AlarmRules.snoozed(Alarm(2, 7, 30, days = AlarmRules.EVERY_DAY), now)
        assertEquals(now + AlarmRules.SNOOZE_MS, AlarmRules.next(daily, now, zone))
    }

    @Test
    fun typedTimes() {
        assertEquals(7 to 30, AlarmRules.typed("730"))
        assertEquals(19 to 45, AlarmRules.typed("1945"))
        assertEquals(12 to 0, AlarmRules.typed("1200"))
        assertNull(AlarmRules.typed("75"))
        assertNull(AlarmRules.typed("2460"))
        assertNull(AlarmRules.typed("2500"))
        assertEquals("7" to "30", AlarmRules.typedHour("730") to AlarmRules.typedMinute("730"))
        assertTrue(AlarmRules.needsAmPm(7, is24 = false))
        assertFalse(AlarmRules.needsAmPm(19, is24 = false))
        assertFalse(AlarmRules.needsAmPm(7, is24 = true))
        assertEquals(19, AlarmRules.withAmPm(7, pm = true))
        assertEquals(0, AlarmRules.withAmPm(12, pm = false))
        assertEquals(12, AlarmRules.withAmPm(12, pm = true))
    }

    @Test
    fun alarmWords() {
        val us = Locale.US
        assertEquals("7:30 AM", AlarmWords.time(7, 30, false, us))
        assertEquals("19:30", AlarmWords.time(19, 30, true, us))
        assertEquals("Weekdays", AlarmWords.days(AlarmRules.WEEKDAYS, us))
        assertEquals("Mon, Wed, Fri", AlarmWords.days(setOf(1, 3, 5), us))
        assertEquals("Monday, Wednesday", AlarmWords.days(setOf(1, 3), us, short = false))
        val pills = Alarm(5, 8, 0, days = AlarmRules.EVERY_DAY, message = "Take your pills")
        assertEquals("8:00 AM · Take your pills", AlarmWords.row(pills, false, us))
        assertEquals("Reminder: Take your pills", AlarmWords.ringing(pills, false, us))
        assertEquals(
            "Alarm set for 7:30 AM, Weekdays. It rings in 22 hours, 30 minutes.",
            AlarmWords.saved(Alarm(6, 7, 30, days = AlarmRules.WEEKDAYS), false, us, wed(9, 0) + 22 * 3_600_000L + 30 * 60_000L, wed(9, 0)),
        )
        Lang.choose("es")
        assertEquals("Recordatorio: Take your pills", AlarmWords.ringing(pills, false, Locale.forLanguageTag("es")))
    }

    @Test
    fun alarmsAreSavedExactly() {
        val list = listOf(
            Alarm(1, 7, 30, days = AlarmRules.WEEKDAYS),
            Alarm(2, 20, 0, days = setOf(2, 4), message = "Take your \"blue\" pills", on = false, snoozedUntil = 123L),
        )
        assertEquals(list, Alarms.decode(Alarms.encode(list)))
        assertEquals(emptyList<Alarm>(), Alarms.decode("[]"))
    }

    @Test
    fun calculator() {
        fun keys(s: String): Calc = s.fold(Calc()) { c, k ->
            when (k) {
                in '0'..'9' -> CalcRules.digit(c, k)
                '.' -> CalcRules.point(c)
                '=' -> CalcRules.equals(c)
                'C' -> CalcRules.clear()
                '<' -> CalcRules.delete(c)
                else -> CalcRules.op(c, k)
            }
        }
        assertEquals("15", keys("12+3=").shown)
        assertEquals("0.3", keys("0.1+0.2=").shown)
        assertEquals("15", keys("2+3×3=").shown) // worked out in order, like a pocket calculator
        assertEquals("3.33333333333", keys("10÷3=").shown)
        assertEquals("-7", keys("3−10=").shown)
        assertEquals("Can't divide by zero", keys("5÷0=").error)
        assertEquals("12", keys("123<").shown)
        assertEquals("12 +", keys("12+").pending)
        assertEquals("12 plus 3", CalcRules.spokenScreen(keys("12+3")))
        assertEquals("minus 7", CalcRules.spoken("-7"))
        // A new number after an answer starts afresh; a sign after it carries on.
        assertEquals("4", keys("2+2=4").shown)
        assertEquals("8", keys("2+2=×2=").shown)
    }

    @Test
    fun lowBattery() {
        assertEquals(null to 100, BatteryRules.check(50, false, 100))
        assertEquals(20 to 20, BatteryRules.check(20, false, 100))
        assertEquals(null to 20, BatteryRules.check(18, false, 20))
        assertEquals(10 to 10, BatteryRules.check(10, false, 20))
        // Fell straight from 22 to 4: just the lowest warning.
        assertEquals(5 to 5, BatteryRules.check(4, false, 100))
        // Charging starts it afresh.
        assertEquals(null to 100, BatteryRules.check(8, true, 5))
    }

    @Test
    fun lightFinder() {
        // A dark room (dim picture, long exposure, high gain) against a lamp up close.
        val dark = LightRules.level(20.0, 66_000_000L, 3200)
        val room = LightRules.level(120.0, 33_000_000L, 400)
        val lamp = LightRules.level(150.0, 2_000_000L, 50)
        assertTrue(dark < room && room < lamp)
        assertEquals("Dark", LightRules.word(dark))
        assertEquals("Very bright", LightRules.word(lamp))
        assertTrue(LightRules.pitch(lamp.toFloat()) > LightRules.pitch(dark.toFloat()))
    }
}
