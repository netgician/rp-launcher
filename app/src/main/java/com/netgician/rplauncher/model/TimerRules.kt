package com.netgician.rplauncher.model

import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.i18n.tr

/**
 * The Timer tile's one timer, kitchen-timer style. Times are the phone's clock in
 * milliseconds. Kept apart from Android so the tests can check every step.
 */
data class TimerState(
    val phase: Phase = Phase.IDLE,
    /** When it rings, while running. */
    val endsAt: Long = 0L,
    /** What's left, while paused. */
    val leftMs: Long = 0L,
    /** How long it was set for ("the 10 minute timer"). */
    val setMs: Long = 0L,
    /** When it started ringing, so it can stop by itself after a while. */
    val rangAt: Long = 0L,
) {
    enum class Phase { IDLE, RUNNING, PAUSED, RINGING }

    val isActive: Boolean get() = phase == Phase.RUNNING || phase == Phase.PAUSED

    fun left(now: Long): Long = when (phase) {
        Phase.RUNNING -> (endsAt - now).coerceAtLeast(0L)
        Phase.PAUSED -> leftMs
        else -> 0L
    }

    /** Running and at (or a moment before) its time. */
    fun isDue(now: Long): Boolean = phase == Phase.RUNNING && now >= endsAt - TimerRules.EARLY_MS

    fun start(now: Long, ms: Long): TimerState {
        val length = ms.coerceIn(TimerRules.SECOND, TimerRules.MAX_MS)
        return TimerState(Phase.RUNNING, endsAt = now + length, setMs = length)
    }

    fun pause(now: Long): TimerState =
        if (phase == Phase.RUNNING) copy(phase = Phase.PAUSED, leftMs = left(now), endsAt = 0L) else this

    fun resume(now: Long): TimerState =
        if (phase == Phase.PAUSED) copy(phase = Phase.RUNNING, endsAt = now + leftMs, leftMs = 0L) else this

    /** One more minute: added to a running or paused timer, or a new one-minute timer. */
    fun addMinute(now: Long): TimerState = when (phase) {
        Phase.RUNNING -> copy(endsAt = (endsAt + TimerRules.MINUTE).coerceAtMost(now + TimerRules.MAX_MS))
        Phase.PAUSED -> copy(leftMs = (leftMs + TimerRules.MINUTE).coerceAtMost(TimerRules.MAX_MS))
        else -> start(now, TimerRules.MINUTE)
    }

    fun ring(now: Long): TimerState =
        if (phase == Phase.RINGING) this else TimerState(Phase.RINGING, setMs = setMs, rangAt = now)

    fun stop(): TimerState = TimerState()
}

object TimerRules {
    const val SECOND = 1_000L
    const val MINUTE = 60_000L
    const val MAX_MS = 24 * 60 * MINUTE

    /** A ringing timer goes quiet by itself after this long. */
    const val RING_FOR_MS = 10 * MINUTE

    /** An alarm a moment early still counts as on time. */
    const val EARLY_MS = 1_500L

    /** The choices on the Timer screen, in minutes. Anything else is typed in. */
    val PRESETS: List<Int> = listOf(1, 3, 5, 10, 15, 20, 30, 60)

    /** Typed minutes, from 1 up to a day. */
    fun typedMinutes(digits: String): Int? = digits.toIntOrNull()?.takeIf { it in 1..(MAX_MS / MINUTE).toInt() }

    /**
     * After the phone restarts or RP Launcher starts again: a timer that ran out only just
     * now rings; one that ran out long ago, or rang long ago, is cleared.
     */
    fun restored(s: TimerState, now: Long): TimerState = when (s.phase) {
        TimerState.Phase.RUNNING -> when {
            now >= s.endsAt + RING_FOR_MS -> TimerState()
            now >= s.endsAt -> s.ring(now)
            else -> s
        }
        TimerState.Phase.RINGING -> if (now >= s.rangAt + RING_FOR_MS) TimerState() else s
        else -> s
    }

    /**
     * Whole seconds as shown, rounded up: a 10 minute timer shows 10:00 when it starts and
     * 0:01 in its last second, never 0:00 before it rings.
     */
    fun shownSeconds(leftMs: Long): Long = (leftMs.coerceAtLeast(0L) + 999L) / 1000L
}

/** What the timer shows and says. */
object TimerWords {
    /** "4:32" or "1:05:00". */
    fun clock(leftMs: Long): String = CallWords.clock(TimerRules.shownSeconds(leftMs))

    /** "10 minutes", "1 hour, 30 minutes": a length of time to choose or say. */
    fun length(ms: Long): String = CallWords.spokenDuration(TimerRules.shownSeconds(ms))

    /** What's left, for the voice. Over ten minutes it's whole minutes, so it isn't a mouthful. */
    fun left(leftMs: Long): String {
        val s = TimerRules.shownSeconds(leftMs)
        val rounded = if (s >= 600) (s + 59) / 60 * 60 else s
        return tr("%s left", CallWords.spokenDuration(rounded))
    }

    fun started(ms: Long, ringsAt: String): String = tr("Timer set for %s. It rings at %s.", length(ms), ringsAt)

    /** The home tile: "Timer", "4:32 left", "Paused 4:32" or "Timer done". */
    fun tile(s: TimerState, now: Long): String = when (s.phase) {
        TimerState.Phase.IDLE -> tr("Timer")
        TimerState.Phase.RUNNING -> tr("%s left", clock(s.left(now)))
        TimerState.Phase.PAUSED -> tr("Paused %s", clock(s.leftMs))
        TimerState.Phase.RINGING -> tr("Timer done")
    }

    fun tileSpoken(s: TimerState, now: Long): String = when (s.phase) {
        TimerState.Phase.IDLE -> tr("Timer")
        TimerState.Phase.RUNNING -> tr("Timer, %s", left(s.left(now)))
        TimerState.Phase.PAUSED -> tr("Timer paused, %s", left(s.leftMs))
        TimerState.Phase.RINGING -> tr("Timer done. Tap to stop it.")
    }

    /** "Set for 10 minutes", under "Timer done". */
    fun which(s: TimerState): String = if (s.setMs > 0) tr("Set for %s", length(s.setMs)) else ""
}
