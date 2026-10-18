package com.netgician.rplauncher.calls

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Vibes
import com.netgician.rplauncher.texts.QuickReplies
import com.netgician.rplauncher.ui.AtkinsonFamily
import com.netgician.rplauncher.ui.FitText
import com.netgician.rplauncher.ui.InWindow
import com.netgician.rplauncher.ui.LocalLv
import com.netgician.rplauncher.ui.LvStyle
import com.netgician.rplauncher.ui.PagedTiles
import com.netgician.rplauncher.ui.SpokenText
import com.netgician.rplauncher.ui.Tile
import com.netgician.rplauncher.ui.TileIcon
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The call screen: who's calling, large and spoken, with big one-tap Answer, Decline and
 * Hang up. Shows over the lock screen. Everything sits inside the person's viewing window.
 */
class CallActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.BLACK), navigationBarStyle = SystemBarStyle.dark(Color.BLACK))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = ConfigStore(this).load()
        speaker = Speaker(this).apply {
            rate = config.speechRate
            pitch = config.speechPitch
            voiceName = config.voiceName
        }
        val vibes = Vibes(this)
        proximity = runCatching {
            getSystemService(PowerManager::class.java)
                ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
                ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "rplauncher:call")
        }.getOrNull()

        setContent {
            val style = remember { LvStyle(config.theme, AtkinsonFamily, config.textScale) }
            CompositionLocalProvider(LocalLv provides style) {
                val calls by Calls.list.collectAsState()
                val audio by Calls.audio.collectAsState()
                var lastName by remember { mutableStateOf("") }
                var lastSeconds by remember { mutableLongStateOf(0L) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1_000)
                        now = System.currentTimeMillis()
                    }
                }
                val live = calls.filter { it.phase != CallPhase.ENDED }
                val main = live.firstOrNull { it.phase == CallPhase.RINGING }
                    ?: live.firstOrNull { it.phase == CallPhase.ACTIVE }
                    ?: live.firstOrNull { it.phase == CallPhase.DIALING || it.phase == CallPhase.CONNECTING }
                    ?: live.firstOrNull()
                main?.let {
                    lastName = it.name
                    if (it.connectedAt > 0) lastSeconds = (now - it.connectedAt) / 1000
                }

                // Said once per call: who's calling, or who you're calling.
                var announced by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(main?.id, main?.phase) {
                    val c = main ?: return@LaunchedEffect
                    if (announced == c.id + c.phase) return@LaunchedEffect
                    announced = c.id + c.phase
                    when (c.phase) {
                        CallPhase.RINGING -> {
                            vibes.play(longArrayOf(0, 300))
                            delay(600)
                            speaker.speak(CallWords.ringing(c.name), Language.guess(c.name))
                        }
                        CallPhase.DIALING -> speaker.speak("Calling ${c.name}")
                        else -> Unit
                    }
                }

                // Earpiece calls turn the screen off at the ear, so the cheek can't press buttons.
                val earpiece = main?.phase == CallPhase.ACTIVE && audio?.route != android.telecom.CallAudioState.ROUTE_SPEAKER
                LaunchedEffect(earpiece) { proximity(earpiece) }

                // When the last call ends: say so, then close.
                LaunchedEffect(live.isEmpty()) {
                    if (live.isEmpty()) {
                        proximity(false)
                        if (lastName.isNotEmpty()) speaker.speak(CallWords.ended(lastName, lastSeconds))
                        delay(1_800)
                        finish()
                    }
                }

                InWindow(config.window) {
                    if (main == null) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                            FitText("Call ended", style.fg, maxSp = 34f * style.textScale, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        CallScreen(main, live, now, audio?.isMuted == true, audio?.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
                    }
                }
            }
        }
    }

    @Composable
    private fun CallScreen(main: CallInfo, all: List<CallInfo>, now: Long, muted: Boolean, speakerOn: Boolean) {
        val lv = LocalLv.current
        var keypad by remember(main.id) { mutableStateOf(false) }
        var replies by remember(main.id) { mutableStateOf(false) }
        var typed by remember(main.id) { mutableStateOf("") }
        val say: (String) -> Unit = { speaker.speak(it) }
        val held = all.firstOrNull { it.phase == CallPhase.HOLDING && it.id != main.id }

        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpokenText(
                main.name, Modifier.fillMaxWidth().height((56f * lv.textScale).dp), say,
                spoken = "${main.name}. ${CallWords.status(main, now)}", maxSp = 34f, maxLines = 1,
            )
            SpokenText(
                if (keypad && typed.isNotEmpty()) typed else CallWords.status(main, now) + if (held != null) " · ${held.name} on hold" else "",
                Modifier.fillMaxWidth().height((36f * lv.textScale).dp), say, maxSp = 22f,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    main.phase == CallPhase.RINGING && replies -> {
                        val choices = QuickReplies.forLanguage(Locale.getDefault().language).take(5)
                        Grid(listOf("Back") + choices, cols = 1, rows = 6) { item, m ->
                            if (item == "Back") Tile("Back", m, onLongPress = { say("Back") }) { replies = false }
                            else Tile(item, m, onLongPress = { say(item) }) {
                                Calls.declineWithText(main.id, item)
                                say("Declined. Texting: $item")
                            }
                        }
                    }
                    main.phase == CallPhase.RINGING -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tile(
                                "Answer", Modifier.fillMaxWidth().weight(2f), spoken = "Answer the call from ${main.name}",
                                icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true, onLongPress = { say("Answer") },
                            ) { Calls.answer(main.id) }
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Tile(
                                    "Decline", Modifier.weight(1f).fillMaxHeight(), icon = TileIcon.Vector(Icons.Filled.Close),
                                    onLongPress = { say("Decline") },
                                ) { Calls.decline(main.id) }
                                Tile(
                                    "Text back", Modifier.weight(1f).fillMaxHeight(), spoken = "Decline and send a text",
                                    onLongPress = { say("Decline and send a text") },
                                ) { replies = true }
                            }
                        }
                    }
                    keypad -> {
                        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#", "Done")
                        Grid(keys, cols = 3, rows = 5) { k, m ->
                            if (k == "Done") Tile("Done", m, emphasized = true, onLongPress = { say("Done") }) {
                                keypad = false
                                typed = ""
                            } else Tile(k, m, spoken = CallWords.key(k), onLongPress = { say(CallWords.key(k)) }) {
                                Calls.key(main.id, k[0])
                                typed += k
                            }
                        }
                    }
                    else -> {
                        val audio = getSystemService(AudioManager::class.java)
                        val items = buildList {
                            add("hangup")
                            add("speaker"); add("mute")
                            add("louder"); add("quieter")
                            add("keypad")
                            if (held != null) add("swap")
                        }
                        Grid(items, cols = 2, rows = 4) { item, m ->
                            when (item) {
                                "hangup" -> Tile(
                                    "Hang up", m, spoken = "Hang up", icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true,
                                    onLongPress = { say("Hang up") },
                                ) { Calls.hangUp(main.id) }
                                "speaker" -> Tile(
                                    if (speakerOn) "Speaker on" else "Speaker", m, emphasized = speakerOn,
                                    spoken = if (speakerOn) "Speaker is on. Tap to turn it off" else "Turn on the speaker",
                                    onLongPress = { say(if (speakerOn) "Speaker is on" else "Speaker is off") },
                                ) {
                                    Calls.speaker(!speakerOn)
                                }
                                "mute" -> Tile(
                                    if (muted) "Muted" else "Mute", m, emphasized = muted,
                                    spoken = if (muted) "You're muted. Tap to unmute" else "Mute your microphone",
                                    onLongPress = { say(if (muted) "You're muted" else "Not muted") },
                                ) {
                                    Calls.mute(!muted)
                                }
                                "louder" -> Tile("Louder", m, onLongPress = { say("Louder") }) {
                                    audio?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                }
                                "quieter" -> Tile("Quieter", m, onLongPress = { say("Quieter") }) {
                                    audio?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                }
                                "keypad" -> Tile("Keypad", m, spoken = "Keypad, for menus that ask you to press a number", onLongPress = { say("Keypad") }) {
                                    keypad = true
                                }
                                else -> Tile("Switch to ${held?.name}", m, onLongPress = { say("Switch calls") }) {
                                    held?.let { Calls.hold(it.id, false) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A plain grid of big tiles from the shared layout rules, without its own header. */
    @Composable
    private fun <T> Grid(items: List<T>, cols: Int, rows: Int, tile: @Composable (T, Modifier) -> Unit) {
        var page by remember(items.size) { mutableIntStateOf(0) }
        PagedTiles(
            items = items, page = page, onPage = { p, _ -> page = p }, cols = cols, rows = rows,
            onSpeak = { speaker.speak(it) }, header = { }, headerHeight = 0f,
        ) { item, m, _ -> tile(item, m) }
    }

    private fun proximity(on: Boolean) {
        val lock = proximity ?: return
        runCatching {
            if (on && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
            if (!on && lock.isHeld) lock.release()
        }
    }

    override fun onDestroy() {
        proximity(false)
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            runCatching {
                context.startActivity(
                    android.content.Intent(context, CallActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
    }
}
