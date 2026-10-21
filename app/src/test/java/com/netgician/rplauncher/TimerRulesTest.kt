package com.netgician.rplauncher

import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.model.TimerRules.MINUTE
import com.netgician.rplauncher.model.TimerState
import com.netgician.rplauncher.model.TimerState.Phase
import com.netgician.rplauncher.model.TimerWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Timer tile: counting down, pausing, one more minute, ringing, restarts, and what it says. */
class TimerRulesTest {
    private val t0 = 1_000_000_000_000L

    @After
    fun english() = Lang.choose("en")

    @Test
    fun countsDownAndRingsOnTime() {
        val s = TimerState().start(t0, 10 * MINUTE)
        assertEquals(Phase.RUNNING, s.phase)
        assertEquals(10 * MINUTE, s.left(t0))
        assertEquals("10:00", TimerWords.clock(s.left(t0)))
        assertEquals("9:59", TimerWords.clock(s.left(t0 + 1_000)))
        // The last second shows 0:01, never 0:00 before it rings.
        assertEquals("0:01", TimerWords.clock(s.left(t0 + 10 * MINUTE - 400)))
        assertFalse(s.isDue(t0 + 9 * MINUTE))
        assertTrue(s.isDue(t0 + 10 * MINUTE))
        // An alarm a moment early still counts.
        assertTrue(s.isDue(t0 + 10 * MINUTE - 1_000))
    }

    @Test
    fun pauseKeepsWhatsLeft() {
        val s = TimerState().start(t0, 5 * MINUTE)
        val paused = s.pause(t0 + 2 * MINUTE)
        assertEquals(Phase.PAUSED, paused.phase)
        assertEquals(3 * MINUTE, paused.left(t0 + 60 * MINUTE))
        assertFalse(paused.isDue(t0 + 60 * MINUTE))
        val going = paused.resume(t0 + 60 * MINUTE)
        assertEquals(Phase.RUNNING, going.phase)
        assertEquals(t0 + 63 * MINUTE, going.endsAt)
    }

    @Test
    fun oneMoreMinute() {
        val s = TimerState().start(t0, 5 * MINUTE)
        assertEquals(t0 + 6 * MINUTE, s.addMinute(t0).endsAt)
        assertEquals(4 * MINUTE, s.pause(t0 + 2 * MINUTE).addMinute(t0).leftMs)
        // From ringing (or nothing), it's a new one-minute timer.
        val again = s.ring(t0 + 5 * MINUTE).addMinute(t0 + 5 * MINUTE)
        assertEquals(Phase.RUNNING, again.phase)
        assertEquals(t0 + 6 * MINUTE, again.endsAt)
        // Never more than a day.
        val long = TimerState().start(t0, TimerRules.MAX_MS).addMinute(t0)
        assertEquals(t0 + TimerRules.MAX_MS, long.endsAt)
    }

    @Test
    fun ringingRemembersWhatItWasSetFor() {
        val r = TimerState().start(t0, 10 * MINUTE).ring(t0 + 10 * MINUTE)
        assertEquals(Phase.RINGING, r.phase)
        assertEquals(10 * MINUTE, r.setMs)
        assertEquals("Set for 10 minutes", TimerWords.which(r))
        assertEquals(Phase.IDLE, r.stop().phase)
    }

    @Test
    fun afterARestart() {
        val s = TimerState().start(t0, 10 * MINUTE)
        assertEquals(s, TimerRules.restored(s, t0 + 5 * MINUTE))
        assertEquals(Phase.RINGING, TimerRules.restored(s, t0 + 11 * MINUTE).phase)
        // Ran out long ago: cleared, not rung late.
        assertEquals(Phase.IDLE, TimerRules.restored(s, t0 + 30 * MINUTE).phase)
        val ringing = s.ring(t0 + 10 * MINUTE)
        assertEquals(Phase.RINGING, TimerRules.restored(ringing, t0 + 12 * MINUTE).phase)
        assertEquals(Phase.IDLE, TimerRules.restored(ringing, t0 + 25 * MINUTE).phase)
    }

    @Test
    fun typedMinutesAndSeconds() {
        assertEquals(45 * 1_000L, TimerRules.typed("45"))
        assertEquals(MINUTE + 30_000, TimerRules.typed("130"))
        assertEquals(6 * MINUTE, TimerRules.typed("600"))
        assertEquals(25 * MINUTE, TimerRules.typed("2500"))
        assertEquals(999 * MINUTE + 59_000, TimerRules.typed("99959"))
        assertNull(TimerRules.typed("0"))
        assertNull(TimerRules.typed("000"))
        assertNull(TimerRules.typed("123456"))
        assertNull(TimerRules.typed(""))
        // The two boxes, as they fill in.
        assertEquals("0" to "05", TimerRules.typedMinutes("5") to TimerRules.typedSeconds("5"))
        assertEquals("1" to "30", TimerRules.typedMinutes("130") to TimerRules.typedSeconds("130"))
        assertEquals("6" to "00", TimerRules.typedMinutes("600") to TimerRules.typedSeconds("600"))
        assertEquals("25" to "00", TimerRules.typedMinutes("2500") to TimerRules.typedSeconds("2500"))
        assertEquals("0" to "00", TimerRules.typedMinutes("") to TimerRules.typedSeconds(""))
        assertEquals("6 minutes", TimerWords.length(TimerRules.typed("600")!!))
        assertEquals("1:30", TimerWords.clock(TimerRules.typed("130")!!))
        assertEquals("1 minute, 30 seconds", TimerWords.length(TimerRules.typed("130")!!))
        assertEquals("30 seconds", TimerWords.length(TimerRules.PRESETS.first()))
    }

    @Test
    fun words() {
        assertEquals("1 minute", TimerWords.length(MINUTE))
        assertEquals("1 hour", TimerWords.length(60 * MINUTE))
        assertEquals("1 hour, 30 minutes", TimerWords.length(90 * MINUTE))
        assertEquals("1:30:00", TimerWords.clock(90 * MINUTE))
        assertEquals("Time left: 4 minutes, 32 seconds", TimerWords.left(4 * MINUTE + 32_000))
        // Over ten minutes, whole minutes only.
        assertEquals("Time left: 25 minutes", TimerWords.left(24 * MINUTE + 10_000))
        assertEquals("Timer set for 10 minutes. It rings at 3:15 PM.", TimerWords.started(10 * MINUTE, "3:15 PM"))
        val s = TimerState().start(t0, 5 * MINUTE)
        assertEquals("5:00 left", TimerWords.tile(s, t0))
        assertEquals("Paused 3:00", TimerWords.tile(s.pause(t0 + 2 * MINUTE), t0))
        assertEquals("Timer done", TimerWords.tile(s.ring(t0), t0))
        assertEquals("Timer", TimerWords.tile(TimerState(), t0))
    }

    @Test
    fun spanish() {
        Lang.choose("es")
        assertEquals("Temporizador de 10 minutos. Suena a las 3:15 PM.", TimerWords.started(10 * MINUTE, "3:15 PM"))
        assertEquals("Se acabó el tiempo", TimerWords.tile(TimerState().start(t0, MINUTE).ring(t0), t0))
        assertEquals("Tiempo restante: 1 minuto", TimerWords.left(MINUTE))
        assertEquals("Faltan 5:00", TimerWords.tile(TimerState().start(t0, 5 * MINUTE), t0))
    }
}
