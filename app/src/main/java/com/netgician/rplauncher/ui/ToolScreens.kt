package com.netgician.rplauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.R
import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.Calc
import com.netgician.rplauncher.model.CalcRules
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.VoiceNote
import com.netgician.rplauncher.system.VoiceNotes
import com.netgician.rplauncher.texts.TextSpeech
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

// ==================================================================== talking clock

/** "It's 3:15 PM. Wednesday, October 21." */
internal fun clockSpoken(context: android.content.Context, now: Long = System.currentTimeMillis()): String {
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(Lang.locale, "EEEEMMMMd"), Lang.locale).format(Date(now))
    return tr("It's %s. %s.", time, date)
}

/** The Clock tile: the time and the short date, large; a tap says them. */
@Composable
internal fun ClockTile(state: AppState, m: Modifier, pos: TilePos) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(Lang.locale, "EEEMMMd"), Lang.locale).format(Date(now))
    val spoken = clockSpoken(context, now)
    Tile("$time\n$date", m, spoken = spoken, icon = null, onLongPress = longPress(state, spoken, pos)) {
        state.open(TileSpec.Clock)
    }
}

// ==================================================================== voice notes

private val MIC_ICON = TileIcon.Drawable(R.drawable.ic_mic)

private fun noteWhen(context: android.content.Context, n: VoiceNote): String =
    TextSpeech.whenSaid(n.at, System.currentTimeMillis(), time = { DateFormat.getTimeFormat(context).format(Date(it)) })

/** The Voice notes tile: record a new one, or play one already kept. */
@Composable
fun NotesScreen(state: AppState) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(VoiceNotes.list(context)) }
    LaunchedEffect(Unit) {
        notes = VoiceNotes.list(context)
        state.say(
            when (notes.size) {
                0 -> tr("Voice notes. None yet. Tap Record a note to make one.")
                1 -> tr("Voice notes, 1 note")
                else -> tr("Voice notes, %s notes", notes.size)
            },
        )
    }
    val choices = buildList {
        add(Control(tr("Back"), spoken = tr("Back to the home screen"), icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) })
        add(Control(tr("Record a note"), icon = MIC_ICON, emphasized = notes.isEmpty()) { state.go(Screen.NoteRecord) })
        notes.forEach { n ->
            val whenSaid = noteWhen(context, n)
            add(
                Control("$whenSaid · ${CallWords.clock(n.seconds.toLong())}", spoken = tr("Note from %s, %s", whenSaid, CallWords.spokenDuration(n.seconds.toLong()))) {
                    state.go(Screen.Note(n.file.name))
                },
            )
        }
    }
    ChoiceList(state, tr("Voice notes"), choices = choices)
}

/** Records a note, up to five minutes: a buzz when it starts, then Stop and save. */
@Composable
fun NoteRecordScreen(state: AppState) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }

    fun stop(save: Boolean) {
        val r = recorder ?: return
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        val f = file
        file = null
        if (save && ok && f != null && seconds > 0) {
            val kept = VoiceNotes.keep(context, f, seconds)
            state.say(tr("Saved. %s.", CallWords.spokenDuration(seconds.toLong())))
            state.go(Screen.Note(kept.name))
        } else {
            f?.delete()
            state.say(if (save) tr("Nothing was recorded. Try again.") else tr("Not saved"))
            if (!save) state.go(Screen.Notes)
        }
    }

    fun start() {
        state.speaker.stop()
        val f = VoiceNotes.newFile(context)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(VoiceNotes.MAX_SECONDS * 1_000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(save = true) }
            r.prepare()
            r.start()
            file = f
            seconds = 0
            recorder = r
            state.vibes.play(longArrayOf(0, 150))
        } catch (e: Exception) {
            r.release()
            f.delete()
            state.say(tr("The microphone couldn't start."))
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else state.say(tr("The microphone is not allowed. A helper can allow it in the Setup checklist."))
    }
    val recording = recorder != null
    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            seconds++
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder?.let { runCatching { it.stop() }; it.release() }
            file?.delete()
        }
    }
    LaunchedEffect(Unit) { state.say(tr("Tap Start recording, then speak. A buzz means it's recording.")) }
    val choices = if (recording) {
        listOf(
            Control(tr("Stop and save"), emphasized = true) { stop(save = true) },
            Control(tr("Cancel"), spoken = tr("Cancel, don't save"), icon = TileIcon.Vector(Icons.Filled.Close)) { stop(save = false) },
        )
    } else {
        listOf(
            Control(tr("Start recording"), icon = MIC_ICON, emphasized = true) {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (ok) start() else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            Control(tr("Back")) { state.go(Screen.Notes) },
        )
    }
    val title = if (recording) tr("Recording %s", CallWords.clock(seconds.toLong())) else tr("New voice note")
    ChoiceList(state, title, spokenTitle = if (recording) tr("Recording, %s", CallWords.spokenDuration(seconds.toLong())) else title, choices = choices)
}

/** One note: Play, Delete (tap twice), Back. */
@Composable
fun NoteScreen(state: AppState, name: String) {
    val context = LocalContext.current
    val note = remember(name) { VoiceNotes.list(context).firstOrNull { it.file.name == name } }
    if (note == null) {
        LaunchedEffect(Unit) { state.go(Screen.Notes) }
        return
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var deleting by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val whenSaid = noteWhen(context, note)
    val stopPlaying = {
        player?.release()
        player = null
    }
    val choices = listOf(
        if (player == null) {
            Control(tr("Play"), spoken = tr("Play the note"), icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) {
                state.speaker.stop()
                player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(note.file.absolutePath)
                        setOnCompletionListener { stopPlaying() }
                        prepare()
                        start()
                    }
                }.getOrNull()
                if (player == null) state.say(tr("That note couldn't be played."))
            }
        } else {
            Control(tr("Stop"), spoken = tr("Stop playing"), emphasized = true) { stopPlaying() }
        },
        Control(if (deleting) tr("Tap again to delete") else tr("Delete"), emphasized = deleting) {
            if (!deleting) {
                deleting = true
                state.say(tr("Tap Delete again to delete it."))
            } else {
                stopPlaying()
                VoiceNotes.delete(note)
                state.say(tr("Deleted"))
                state.go(Screen.Notes)
            }
        },
        Control(tr("Back"), spoken = tr("Back to the notes")) { state.go(Screen.Notes) },
    )
    ChoiceList(
        state, "$whenSaid · ${CallWords.clock(note.seconds.toLong())}",
        spokenTitle = tr("Note from %s, %s", whenSaid, CallWords.spokenDuration(note.seconds.toLong())), choices = choices,
    )
}

// ==================================================================== calculator

/** A big four-function calculator that says each key and the answer. */
@Composable
fun CalculatorScreen(state: AppState) {
    val lv = LocalLv.current
    var c by remember { mutableStateOf(Calc()) }
    LaunchedEffect(Unit) { state.say(tr("Calculator")) }
    val keys = listOf(
        "clear", "delete", "say", "÷",
        "7", "8", "9", "×",
        "4", "5", "6", "−",
        "1", "2", "3", "+",
        "back", "0", ".", "=",
    )
    InWindow(state.config.window) {
        PagedTiles(
            items = keys, page = 0, onPage = { _, _ -> }, cols = 4, rows = 5, onSpeak = state::say,
            header = { m ->
                val shown = c.error ?: c.shown
                val spoken = CalcRules.spokenScreen(c)
                Column(
                    m.semantics(mergeDescendants = true) { contentDescription = spoken }.clickable { state.say(spoken) },
                    horizontalAlignment = Alignment.End,
                ) {
                    FitText(c.pending.ifEmpty { " " }, lv.fg, maxSp = 24f * lv.textScale, maxLines = 1, modifier = Modifier.fillMaxWidth().weight(1f), align = androidx.compose.ui.text.style.TextAlign.End)
                    FitText(shown, lv.fg, maxSp = 64f * lv.textScale, maxLines = 1, modifier = Modifier.fillMaxWidth().weight(2f), align = androidx.compose.ui.text.style.TextAlign.End)
                }
            },
            minTarget = 0f,
            headerHeight = 120f * state.config.textScale,
        ) { key, m, _ ->
            when (key) {
                "clear" -> Tile("C", m, spoken = tr("Clear everything")) {
                    c = CalcRules.clear()
                    state.say(tr("Cleared"))
                }
                "delete" -> Tile(tr("Del"), m, spoken = tr("Delete the last digit")) {
                    c = CalcRules.delete(c)
                    state.say(CalcRules.spoken(c.shown))
                }
                "say" -> Tile(tr("Say"), m, spoken = tr("Say the number")) { state.say(CalcRules.spokenScreen(c)) }
                "back" -> Tile(tr("Back"), m, spoken = tr("Back to the home screen")) { state.go(Screen.Home) }
                "=" -> Tile("=", m, spoken = tr("equals"), emphasized = true) {
                    c = CalcRules.equals(c)
                    state.say(c.error ?: tr("equals %s", CalcRules.spoken(c.shown)))
                }
                "÷", "×", "−", "+" -> Tile(key, m, spoken = CalcRules.keyName(key[0])) {
                    c = CalcRules.op(c, key[0])
                    state.say(c.error ?: CalcRules.keyName(key[0]))
                }
                "." -> Tile(".", m, spoken = tr("point")) {
                    c = CalcRules.point(c)
                    state.say(tr("point"))
                }
                else -> Tile(key, m) {
                    c = CalcRules.digit(c, key[0])
                    state.say(key)
                }
            }
        }
    }
}

// ==================================================================== music

/** The Music tile: what's playing, Play or Pause, Next, Previous, Louder and Quieter. */
@Composable
fun MediaScreen(state: AppState) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(com.netgician.rplauncher.system.Media.now(context)) }
    // Null when the phone won't say (no Alerts access): then the button is "Play or pause".
    var playing by remember { mutableStateOf(com.netgician.rplauncher.system.Media.now(context)?.playing) }
    LaunchedEffect(Unit) {
        while (true) {
            now = com.netgician.rplauncher.system.Media.now(context)
            playing = now?.playing
            delay(1_000)
        }
    }
    val what = now?.let { n -> listOfNotNull(n.title, n.artist).joinToString(" – ").ifBlank { n.app } }
    LaunchedEffect(Unit) {
        state.say(
            when {
                what != null && playing == true -> tr("Music. Playing: %s", what)
                what != null -> tr("Music. Paused: %s", what)
                else -> tr("Music. Tap Play to start or go on with what you were playing.")
            },
        )
        if (!com.netgician.rplauncher.system.AlertsStore.hasAccess(context)) {
            state.speaker.speak(tr("To hear what's playing, a helper can turn on Alerts access in the Setup checklist."), flush = false)
        }
    }
    val title = when {
        what != null && playing == true -> tr("Playing: %s", what)
        what != null -> tr("Paused: %s", what)
        else -> tr("Music")
    }
    val media = com.netgician.rplauncher.system.Media
    ChoiceList(
        state, title,
        choices = listOf(
            Control(
                when (playing) { true -> tr("Pause"); false -> tr("Play"); null -> tr("Play or pause") },
                icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true,
            ) {
                media.playPause(context)
                when (playing) {
                    true -> state.say(tr("Paused"))
                    false -> state.say(tr("Playing"))
                    null -> Unit
                }
                playing = playing?.not()
            },
            Control(tr("Next track")) {
                media.next(context)
                state.say(tr("Next track"))
            },
            Control(tr("Previous track")) {
                media.previous(context)
                state.say(tr("Previous track"))
            },
            Control(tr("Louder")) { state.say(tr("Volume %s percent", media.volume(context, up = true))) },
            Control(tr("Quieter")) { state.say(tr("Volume %s percent", media.volume(context, up = false))) },
            Control(tr("Back"), spoken = tr("Back to the home screen"), icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) },
        ),
    )
}
