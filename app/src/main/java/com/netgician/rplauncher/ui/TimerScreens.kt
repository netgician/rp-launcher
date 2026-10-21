package com.netgician.rplauncher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.R
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.model.TimerState
import com.netgician.rplauncher.model.TimerWords
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.system.Timer
import kotlinx.coroutines.delay
import java.util.Date

private val TIMER_ICON = TileIcon.Drawable(R.drawable.ic_timer)

/** The time of day it rings, "3:15 PM", in the phone's own format. */
private fun ringsAt(context: android.content.Context, at: Long): String = DateFormat.getTimeFormat(context).format(Date(at))

/** Starts a timer, says when it rings, and shows the countdown. */
internal fun startTimer(state: AppState, ms: Long) {
    val context = state.activityContext
    Timer.start(context, ms)
    state.say(TimerWords.started(ms, ringsAt(context, System.currentTimeMillis() + ms)))
    when {
        !Timer.notificationsAllowed(context) ->
            state.speaker.speak(tr("To hear it while the phone is locked, a helper can allow notifications in the Setup checklist."), flush = false)
        !Timer.canBeExact(context) ->
            state.speaker.speak(tr("It may ring late while the phone is locked. A helper can fix this in the Setup checklist."), flush = false)
    }
    state.go(Screen.Timer)
}

/** The Timer tile: choose how long, or the countdown when a timer is on. */
@Composable
fun TimerScreen(state: AppState) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Timer.load(context) }
    val timer by Timer.state.collectAsState()
    if (timer.phase == TimerState.Phase.IDLE) TimerPick(state) else TimerCountdown(state, timer)
}

/** Big choices for how long; anything else is typed in. */
@Composable
private fun TimerPick(state: AppState) {
    LaunchedEffect(Unit) { state.say(tr("Timer. How long?")) }
    // 0 is Back, -1 is "Other time"; the rest are minutes.
    val items = listOf(0) + TimerRules.PRESETS + listOf(-1)
    InWindow(state.config.window) {
        PagedTiles(
            items = items,
            page = state.listPage,
            onPage = { p, n ->
                state.listPage = p
                state.onPageTurned(p, n)
            },
            cols = state.config.homeCols,
            rows = state.config.rows + 1,
            onSpeak = state::say,
            header = { m -> SpokenText(tr("How long?"), m, state::say, spoken = tr("Timer. How long?")) },
        ) { item, m, pos ->
            when (item) {
                0 -> Tile(
                    tr("Back"), m, spoken = tr("Back to the home screen"), icon = TileIcon.Vector(Icons.Filled.Home),
                    onLongPress = longPress(state, tr("Back to the home screen"), pos),
                ) { state.go(Screen.Home) }
                -1 -> Tile(
                    tr("Other time"), m, spoken = tr("Other time: type the minutes"),
                    onLongPress = longPress(state, tr("Other time: type the minutes"), pos),
                ) { state.go(Screen.NumberPad(NumberPurpose.TIMER)) }
                else -> {
                    val ms = item * TimerRules.MINUTE
                    val label = TimerWords.length(ms)
                    Tile(label, m, spoken = tr("Timer for %s", label), onLongPress = longPress(state, tr("Timer for %s", label), pos)) {
                        startTimer(state, ms)
                    }
                }
            }
        }
    }
}

/** The countdown, as big as the window allows, with Pause, 1 more minute, Stop timer and Home. */
@Composable
private fun TimerCountdown(state: AppState, s: TimerState) {
    val context = LocalContext.current
    val lv = LocalLv.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase, s.endsAt) {
        while (s.phase == TimerState.Phase.RUNNING) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    val left = s.left(now)
    val ringing = s.phase == TimerState.Phase.RINGING
    val big = if (ringing) tr("Timer done") else TimerWords.clock(left)
    val line = when (s.phase) {
        TimerState.Phase.RUNNING -> tr("Rings at %s", ringsAt(context, s.endsAt))
        TimerState.Phase.PAUSED -> tr("Paused")
        else -> TimerWords.which(s)
    }
    val spoken = when (s.phase) {
        TimerState.Phase.RUNNING -> "${TimerWords.left(left)}. $line."
        TimerState.Phase.PAUSED -> tr("Paused, %s", TimerWords.left(left))
        else -> tr("Timer done. Tap Stop.")
    }
    // Said when the screen opens, unless the timer was set a moment ago (that was just said).
    LaunchedEffect(Unit) {
        val justSet = s.phase == TimerState.Phase.RUNNING && System.currentTimeMillis() - (s.endsAt - s.setMs) < 3_000
        if (!justSet) state.say(if (s.phase == TimerState.Phase.RUNNING) tr("Timer, %s", spoken) else spoken)
    }
    val items = if (ringing) listOf("stop", "more", "home") else listOf(if (s.phase == TimerState.Phase.PAUSED) "resume" else "pause", "more", "cancel", "home")
    val speech = state.config.visionMode == VisionMode.SPEECH
    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpokenText(big, Modifier.fillMaxWidth().weight(1f), state::say, spoken = spoken, maxSp = 120f)
            SpokenText(line, Modifier.fillMaxWidth().height((36f * lv.textScale).dp), state::say, maxSp = 24f)
            Box(Modifier.fillMaxWidth().weight(if (speech) 2f else 1.4f)) {
                var page by remember(items.size) { mutableIntStateOf(0) }
                PagedTiles(
                    items = items, page = page, onPage = { p, n -> page = p; state.onPageTurned(p, n) },
                    cols = if (speech) 1 else 2, rows = if (speech) items.size else 2,
                    onSpeak = state::say, header = { }, headerHeight = 0f,
                ) { item, m, pos -> TimerButton(state, item, m, pos) }
            }
        }
    }
}

/** One button on the countdown screen. */
private class TimerAction(
    val label: String,
    val spoken: String,
    val icon: TileIcon? = null,
    val emphasized: Boolean = false,
    val run: () -> Unit,
)

@Composable
private fun TimerButton(state: AppState, item: String, m: Modifier, pos: TilePos) {
    val context = LocalContext.current
    val a = when (item) {
        "pause" -> TimerAction(tr("Pause"), tr("Pause the timer")) {
            Timer.pause(context)
            state.say(tr("Paused, %s", TimerWords.left(Timer.state.value.leftMs)))
        }
        "resume" -> TimerAction(tr("Go on"), tr("Go on with the timer"), TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) {
            Timer.resume(context)
            state.say(tr("Going again. It rings at %s.", ringsAt(context, Timer.state.value.endsAt)))
        }
        "more" -> TimerAction(tr("1 more minute"), tr("Add 1 more minute"), TileIcon.Vector(Icons.Filled.Add)) {
            Timer.addMinute(context)
            state.say(tr("1 minute added. %s", TimerWords.left(Timer.state.value.left(System.currentTimeMillis()))))
        }
        "cancel" -> TimerAction(tr("Stop timer"), tr("Stop the timer"), TileIcon.Vector(Icons.Filled.Close)) {
            Timer.cancel(context)
            state.say(tr("Timer stopped"))
            state.go(Screen.Home)
        }
        "stop" -> TimerAction(tr("Stop"), tr("Stop the timer"), TileIcon.Vector(Icons.Filled.Close), emphasized = true) {
            Timer.stop(context)
            state.say(tr("Timer stopped"))
            state.go(Screen.Home)
        }
        else -> TimerAction(tr("Home"), tr("Home. The timer keeps going."), TileIcon.Vector(Icons.Filled.Home)) {
            state.go(Screen.Home)
        }
    }
    Tile(a.label, m, spoken = a.spoken, icon = a.icon, emphasized = a.emphasized, onLongPress = longPress(state, a.spoken, pos), onClick = a.run)
}

/** The home screen's Timer tile: the time left while a timer is on, and "Timer done" while it rings. */
@Composable
internal fun TimerTile(state: AppState, m: Modifier, pos: TilePos) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Timer.load(context) }
    val s by Timer.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase, s.endsAt) {
        while (s.phase == TimerState.Phase.RUNNING) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
        now = System.currentTimeMillis()
    }
    val spoken = TimerWords.tileSpoken(s, now)
    Tile(
        label = TimerWords.tile(s, now),
        modifier = m,
        spoken = spoken,
        icon = TIMER_ICON,
        emphasized = s.phase != TimerState.Phase.IDLE,
        onLongPress = longPress(state, spoken, pos),
    ) { state.open(TileSpec.Timer) }
}

/** The Timer tile's icon, for Setup's tile list. */
internal val timerIcon: TileIcon get() = TIMER_ICON
