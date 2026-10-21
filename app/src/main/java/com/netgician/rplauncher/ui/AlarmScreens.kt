package com.netgician.rplauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.R
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.Alarm
import com.netgician.rplauncher.model.AlarmRules
import com.netgician.rplauncher.model.AlarmWords
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.Alarms
import kotlinx.coroutines.delay

/**
 * A new or changed alarm or reminder, built up one screen at a time: the message (reminders),
 * the time, then the days. [only] changes just one part of an existing one, then saves.
 */
data class AlarmDraft(
    val reminder: Boolean,
    val editing: Long? = null,
    val only: Step? = null,
    val message: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val days: Set<Int>? = null,
) {
    enum class Step { MESSAGE, TIME, DAYS }
}

private fun is24(state: AppState): Boolean = DateFormat.is24HourFormat(state.activityContext)

/** Goes to the next part still to choose, or saves when everything's chosen. */
internal fun nextAlarmStep(state: AppState, d: AlarmDraft) {
    state.alarmDraft = d
    when {
        d.only != null -> saveAlarm(state, d)
        d.reminder && d.message == null -> state.go(Screen.AlarmMessage)
        d.hour == null || d.minute == null -> state.go(Screen.AlarmTime)
        d.days == null -> state.go(Screen.AlarmDays)
        else -> saveAlarm(state, d)
    }
}

private fun saveAlarm(state: AppState, d: AlarmDraft) {
    val context = state.activityContext
    val base = d.editing?.let { Alarms.get(context, it) }
    val a = Alarm(
        id = base?.id ?: Alarms.newId(),
        hour = d.hour ?: base?.hour ?: 7,
        minute = d.minute ?: base?.minute ?: 0,
        days = d.days ?: base?.days ?: emptySet(),
        message = if (d.reminder) (d.message ?: base?.message) else null,
        on = true,
    )
    val next = Alarms.put(context, a)
    state.say(AlarmWords.saved(a, is24(state), Lang.locale, next, System.currentTimeMillis()))
    state.alarmDraft = null
    state.go(if (d.editing != null) Screen.AlarmEdit(a.id, d.reminder) else Screen.AlarmList(d.reminder))
}

/** Where Back goes from each step. */
internal fun alarmBack(state: AppState, s: Screen) {
    val d = state.alarmDraft
    val list = Screen.AlarmList(d?.reminder ?: false)
    val edit = d?.editing?.let { Screen.AlarmEdit(it, d.reminder) }
    state.go(
        when (s) {
            Screen.AlarmMessage -> edit ?: list
            Screen.AlarmTime -> when {
                edit != null -> edit
                d?.reminder == true -> Screen.AlarmMessage
                else -> list
            }
            Screen.AlarmAmPm -> Screen.AlarmTime
            Screen.AlarmDays -> edit ?: Screen.AlarmTime
            Screen.AlarmPickDays -> Screen.AlarmDays
            else -> list
        },
    )
}

/** The Alarms or Reminders tile: a new one, and each one already set. */
@Composable
fun AlarmListScreen(state: AppState, reminders: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Alarms.load(context) }
    val all by Alarms.list.collectAsState()
    val items = all.filter { it.isReminder == reminders }
    val is24 = is24(state)
    LaunchedEffect(Unit) {
        state.say(
            when {
                items.isEmpty() && reminders -> tr("Reminders. None yet. Tap New reminder to add one.")
                items.isEmpty() -> tr("Alarms. None yet. Tap New alarm to add one.")
                reminders -> if (items.size == 1) tr("Reminders, 1 reminder") else tr("Reminders, %s reminders", items.size)
                else -> if (items.size == 1) tr("Alarms, 1 alarm") else tr("Alarms, %s alarms", items.size)
            },
        )
    }
    val choices = buildList {
        add(Control(tr("Back"), spoken = tr("Back to the home screen"), icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) })
        add(
            Control(
                if (reminders) tr("New reminder") else tr("New alarm"), icon = TileIcon.Vector(Icons.Filled.Add), emphasized = items.isEmpty(),
            ) { nextAlarmStep(state, AlarmDraft(reminder = reminders)) },
        )
        items.forEach { a ->
            add(Control(AlarmWords.row(a, is24, Lang.locale), spoken = AlarmWords.rowSpoken(a, is24, Lang.locale), emphasized = a.on) {
                state.go(Screen.AlarmEdit(a.id, reminders))
            })
        }
    }
    ChoiceList(state, if (reminders) tr("Reminders") else tr("Alarms"), choices = choices)
}

/** One alarm or reminder: turn it off or on, change it, or delete it. */
@Composable
fun AlarmEditScreen(state: AppState, id: Long, reminder: Boolean) {
    val context = LocalContext.current
    val all by Alarms.list.collectAsState()
    val a = all.firstOrNull { it.id == id }
    if (a == null) {
        LaunchedEffect(Unit) { state.go(Screen.AlarmList(reminder)) }
        return
    }
    val is24 = is24(state)
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.say(AlarmWords.rowSpoken(a, is24, Lang.locale)) }
    val choices = buildList {
        add(Control(tr("Back"), spoken = tr("Back to the list")) { state.go(Screen.AlarmList(a.isReminder)) })
        add(
            Control(if (a.on) tr("Turn off") else tr("Turn on"), emphasized = !a.on) {
                val next = Alarms.put(context, a.copy(on = !a.on, snoozedUntil = 0L))
                state.say(if (a.on) tr("Turned off") else AlarmWords.saved(a.copy(on = true), is24, Lang.locale, next, System.currentTimeMillis()))
            },
        )
        if (a.isReminder) {
            add(Control(tr("Change the message")) {
                state.alarmDraft = AlarmDraft(reminder = true, editing = a.id, only = AlarmDraft.Step.MESSAGE)
                state.go(Screen.AlarmMessage)
            })
        }
        add(Control(tr("Change the time")) {
            state.alarmDraft = AlarmDraft(reminder = a.isReminder, editing = a.id, only = AlarmDraft.Step.TIME)
            state.go(Screen.AlarmTime)
        })
        add(Control(tr("Change the days"), spoken = tr("Change the days: %s", AlarmWords.days(a.days, Lang.locale, short = false))) {
            state.alarmDraft = AlarmDraft(reminder = a.isReminder, editing = a.id, only = AlarmDraft.Step.DAYS, days = null)
            state.go(Screen.AlarmDays)
        })
        add(
            Control(if (deleting) tr("Tap again to delete") else tr("Delete"), emphasized = deleting) {
                if (!deleting) {
                    deleting = true
                    state.say(tr("Tap Delete again to delete it."))
                } else {
                    Alarms.delete(context, a.id)
                    state.say(tr("Deleted"))
                    state.go(Screen.AlarmList(a.isReminder))
                }
            },
        )
    }
    ChoiceList(state, AlarmWords.row(a, is24, Lang.locale), spokenTitle = AlarmWords.rowSpoken(a, is24, Lang.locale), choices = choices)
}

/** A reminder's message, spoken (and read back to check), or typed. */
@Composable
fun AlarmMessageScreen(state: AppState) {
    val context = LocalContext.current
    val d = state.alarmDraft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.AlarmList(true)) }
        return
    }
    val old = d.editing?.let { Alarms.get(context, it)?.message }
    var heard by remember { mutableStateOf(d.message ?: old) }
    var typing by remember { mutableStateOf(false) }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val words = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == Activity.RESULT_OK && !words.isNullOrBlank()) {
            heard = words.replaceFirstChar { it.uppercase() }
            state.say(tr("The reminder will say: %s. Tap Next if that's right, or Say it again.", heard!!))
        } else {
            state.say(tr("Nothing heard. Tap Speak to try again."))
        }
    }
    LaunchedEffect(Unit) {
        state.say(if (heard == null) tr("What should the reminder say? Tap Speak, then say it, like: Take your pills.") else tr("The reminder says: %s", heard!!))
    }
    if (typing) {
        TypingBox(state, heard.orEmpty()) { typed ->
            typing = false
            if (typed.isNotBlank()) {
                heard = typed
                state.say(tr("The reminder will say: %s. Tap Next if that's right.", typed))
            }
        }
        return
    }
    val speak = {
        state.speaker.stop()
        try {
            listen.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Say the reminder")),
            )
        } catch (e: ActivityNotFoundException) {
            state.say(tr("Speech typing isn't available on this phone. Use Keyboard instead."))
        }
    }
    val h = heard
    val choices = buildList {
        add(Control(tr("Back")) { alarmBack(state, Screen.AlarmMessage) })
        if (h == null) {
            add(Control(tr("Speak"), spoken = tr("Speak the reminder"), emphasized = true) { speak() })
        } else {
            add(Control(if (d.only != null) tr("Save") else tr("Next"), spoken = if (d.only != null) tr("Save the message") else tr("Next: choose the time"), emphasized = true) {
                nextAlarmStep(state, d.copy(message = h))
            })
            add(Control(tr("Say it again")) { speak() })
        }
        add(Control(tr("Keyboard"), spoken = tr("Type it with the keyboard")) { typing = true })
    }
    ChoiceList(state, h ?: tr("What should it say?"), spokenTitle = h?.let { tr("The reminder says: %s", it) } ?: tr("What should the reminder say?"), choices = choices)
}

/** The time of day: an hour box and a minutes box, filled as it's typed (7, 3, 0 shows 7 : 30). */
@Composable
fun AlarmTimeScreen(state: AppState) {
    val d = state.alarmDraft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.Home) }
        return
    }
    val is24 = is24(state)
    var digits by remember { mutableStateOf(StoreShots.takeTypedTime()) }
    LaunchedEffect(Unit) { state.say(tr("Type the time: the hour, then the minutes. For 7:30, type 7, 3, 0.")) }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "next")
    InWindow(state.config.window) {
        PagedTiles(
            items = keys, page = 0, onPage = { _, _ -> }, cols = 3, rows = 4, onSpeak = state::say,
            header = { m -> AlarmTimeBoxes(digits, m, is24, state::say) },
            minTarget = 0f,
            headerHeight = 120f * state.config.textScale,
        ) { key, m, _ ->
            when (key) {
                "delete" -> if (digits.isEmpty()) {
                    Tile(tr("Back"), m) { alarmBack(state, Screen.AlarmTime) }
                } else {
                    Tile(tr("Delete"), m, spoken = tr("Delete the last digit")) {
                        digits = digits.dropLast(1)
                        state.say(tr("Deleted"))
                    }
                }
                "next" -> Tile(if (d.only != null) tr("Save") else tr("Next"), m, emphasized = true) {
                    val t = AlarmRules.typed(digits)
                    when {
                        t == null -> state.say(tr("Type the hour and the minutes, like 7, 3, 0 for 7:30."))
                        AlarmRules.needsAmPm(t.first, is24) -> {
                            state.alarmDraft = d.copy(hour = t.first, minute = t.second)
                            state.go(Screen.AlarmAmPm)
                        }
                        else -> nextAlarmStep(state, d.copy(hour = t.first, minute = t.second))
                    }
                }
                else -> Tile(key, m, spoken = com.netgician.rplauncher.calls.CallWords.key(key)) {
                    if (digits.length < AlarmRules.MAX_DIGITS && !(digits.isEmpty() && key == "0")) {
                        digits += key
                        val t = AlarmRules.typed(digits)
                        state.say(t?.let { (h, mm) -> "$h:${"%02d".format(mm)}" } ?: com.netgician.rplauncher.calls.CallWords.key(key))
                    }
                }
            }
        }
    }
}

/** "7" in the hour box and "30" in the minutes box; a tap says the time. */
@Composable
private fun AlarmTimeBoxes(digits: String, modifier: Modifier, is24: Boolean, onSpeak: (String) -> Unit) {
    val lv = LocalLv.current
    val t = AlarmRules.typed(digits)
    val spoken = when {
        digits.isEmpty() -> tr("Nothing typed yet. Type the hour, then the minutes.")
        t == null -> tr("Not a time yet")
        else -> "${t.first}:${"%02d".format(t.second)}"
    }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = spoken }.clickable { onSpeak(spoken) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(AlarmRules.typedHour(digits) to tr("hour"), AlarmRules.typedMinute(digits) to tr("min")).forEach { (value, unit) ->
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .border(BorderStroke(4.dp, lv.accent), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FitText(value, lv.fg, maxSp = 64f * lv.textScale, maxLines = 1, modifier = Modifier.fillMaxWidth().weight(2f))
                FitText(unit, lv.fg, maxSp = 24f * lv.textScale, maxLines = 1, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** On a 12-hour phone: morning or evening. */
@Composable
fun AlarmAmPmScreen(state: AppState) {
    val d = state.alarmDraft
    val h = d?.hour
    val m = d?.minute
    if (d == null || h == null || m == null) {
        LaunchedEffect(Unit) { state.go(Screen.Home) }
        return
    }
    val am = AlarmWords.time(AlarmRules.withAmPm(h, false), m, false, Lang.locale)
    val pm = AlarmWords.time(AlarmRules.withAmPm(h, true), m, false, Lang.locale)
    LaunchedEffect(Unit) { state.say(tr("Morning or evening? %s or %s", am, pm)) }
    ChoiceList(
        state, tr("Morning or evening?"),
        choices = listOf(
            Control(tr("Back")) { alarmBack(state, Screen.AlarmAmPm) },
            Control(am, spoken = tr("%s, in the morning", am)) { nextAlarmStep(state, d.copy(hour = AlarmRules.withAmPm(h, false))) },
            Control(pm, spoken = tr("%s, in the afternoon or evening", pm)) { nextAlarmStep(state, d.copy(hour = AlarmRules.withAmPm(h, true))) },
        ),
    )
}

/** How often: once, every day, weekdays, weekends, or chosen days. */
@Composable
fun AlarmDaysScreen(state: AppState) {
    val d = state.alarmDraft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.Home) }
        return
    }
    LaunchedEffect(Unit) { state.say(tr("Which days? Once, every day, weekdays, weekends, or choose the days.")) }
    fun pick(days: Set<Int>) = nextAlarmStep(state, d.copy(days = days))
    ChoiceList(
        state, tr("Which days?"),
        choices = listOf(
            Control(tr("Back")) { alarmBack(state, Screen.AlarmDays) },
            Control(tr("Once"), spoken = tr("Once, then it turns off")) { pick(emptySet()) },
            Control(tr("Every day")) { pick(AlarmRules.EVERY_DAY) },
            Control(tr("Weekdays"), spoken = tr("Weekdays, Monday to Friday")) { pick(AlarmRules.WEEKDAYS) },
            Control(tr("Weekends"), spoken = tr("Weekends, Saturday and Sunday")) { pick(AlarmRules.WEEKEND) },
            Control(tr("Choose the days")) { state.go(Screen.AlarmPickDays) },
        ),
    )
}

/** Each day on or off, then Done. */
@Composable
fun AlarmPickDaysScreen(state: AppState) {
    val context = LocalContext.current
    val d = state.alarmDraft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.Home) }
        return
    }
    val start = d.days ?: d.editing?.let { Alarms.get(context, it)?.days } ?: emptySet()
    var chosen by remember { mutableStateOf(start) }
    LaunchedEffect(Unit) { state.say(tr("Tap each day to turn it on or off, then tap Done.")) }
    // 0 is Done, -1 is Back; 1 to 7 are the days.
    val items = listOf(0) + (1..7).toList() + listOf(-1)
    InWindow(state.config.window) {
        PagedTiles(
            items = items, page = state.listPage,
            onPage = { p, n -> state.listPage = p; state.onPageTurned(p, n) },
            cols = 2, rows = 5, onSpeak = state::say,
            header = { m ->
                val now = if (chosen.isEmpty()) tr("No days chosen") else AlarmWords.days(chosen, Lang.locale)
                SpokenText(now, m, state::say, spoken = if (chosen.isEmpty()) tr("No days chosen") else AlarmWords.days(chosen, Lang.locale, short = false))
            },
        ) { item, m, pos ->
            when (item) {
                0 -> Tile(tr("Done"), m, emphasized = chosen.isNotEmpty(), onLongPress = longPress(state, tr("Done"), pos)) {
                    if (chosen.isEmpty()) state.say(tr("Choose at least one day, or go back and choose Once."))
                    else nextAlarmStep(state, d.copy(days = chosen))
                }
                -1 -> Tile(tr("Back"), m, onLongPress = longPress(state, tr("Back"), pos)) { alarmBack(state, Screen.AlarmPickDays) }
                else -> {
                    val on = item in chosen
                    val name = AlarmWords.dayName(item, Lang.locale)
                    val spoken = if (on) tr("%s, on", name) else tr("%s, off", name)
                    Tile(name, m, spoken = spoken, emphasized = on, onLongPress = longPress(state, spoken, pos)) {
                        chosen = if (on) chosen - item else chosen + item
                        state.say(if (on) tr("%s, off", name) else tr("%s, on", name))
                    }
                }
            }
        }
    }
}

/** The home screen's Alarms tile: the next alarm's time, when one is on. */
@Composable
internal fun AlarmsTile(state: AppState, m: Modifier, pos: TilePos) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Alarms.load(context) }
    val all by Alarms.list.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val next = remember(all, now) { Alarms.nextAlarm(context) }
    val is24 = is24(state)
    val time = next?.let { (a, _) -> AlarmWords.time(a.hour, a.minute, is24, Lang.locale) }
    val label = time?.let { tr("Alarm %s", it) } ?: tr("Alarms")
    val spoken = next?.let { (_, at) -> tr("Alarms. Next alarm at %s, in %s.", time!!, AlarmWords.inHowLong(at, now)) } ?: tr("Alarms")
    Tile(label, m, spoken = spoken, icon = TileIcon.Drawable(R.drawable.ic_alarm), emphasized = next != null, onLongPress = longPress(state, spoken, pos)) {
        state.open(TileSpec.Alarms)
    }
}
