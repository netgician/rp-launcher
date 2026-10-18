package com.netgician.rplauncher.calls

import com.netgician.rplauncher.i18n.tr
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
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.model.NightMode
import com.netgician.rplauncher.system.LightSensor
import com.netgician.rplauncher.ui.NightNow
import java.util.Calendar
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The call screen: who's calling, large and spoken, with big one-tap Answer, Decline and
 * Hang up. Shows over the lock screen. Everything sits inside the person's viewing window.
 */
class CallActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private var proximity: PowerManager.WakeLock? = null
    private var lightSensor: LightSensor? = null
    private lateinit var config: com.netgician.rplauncher.model.LauncherConfig

    /** Night colours, the same as the home screen: by clock or by room light, as set in Setup. */
    private var night by mutableStateOf(NightNow.active)

    /** Demo mode's pretend call (Setup, Demo mode): the same screen, with nothing really dialled. */
    private var demo by mutableStateOf(false)
    private val demoCalls = MutableStateFlow<List<CallInfo>>(emptyList())
    private var demoMuted by mutableStateOf(false)
    private var demoSpeaker by mutableStateOf(false)

    private fun startDemo(i: android.content.Intent?) {
        demo = i?.getBooleanExtra(EXTRA_DEMO, false) == true
        if (!demo) return
        val incoming = i?.getBooleanExtra(EXTRA_INCOMING, true) != false
        val name = i?.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Mary" }
        val number = i?.getStringExtra(EXTRA_NUMBER).orEmpty()
        demoCalls.value = listOf(CallInfo("demo", number, name, if (incoming) CallPhase.RINGING else CallPhase.DIALING, 0))
        if (!incoming) lifecycleScope.launch {
            delay(3_000)
            answer("demo")
        }
    }

    // What the buttons do: the real call, or the pretend one.
    private fun answer(id: String) {
        if (demo) demoCalls.update { l -> l.map { it.copy(phase = CallPhase.ACTIVE, connectedAt = System.currentTimeMillis()) } }
        else Calls.answer(id)
    }
    private fun decline(id: String) { if (demo) demoCalls.value = emptyList() else Calls.decline(id) }
    private fun declineWithText(id: String, text: String) { if (demo) demoCalls.value = emptyList() else Calls.declineWithText(id, text) }
    private fun hangUp(id: String) { if (demo) demoCalls.value = emptyList() else Calls.hangUp(id) }
    private fun hold(id: String, on: Boolean) { if (!demo) Calls.hold(id, on) }
    private fun key(id: String, digit: Char) { if (!demo) Calls.key(id, digit) }
    private fun mute(on: Boolean) { if (demo) demoMuted = on else Calls.mute(on) }
    private fun setSpeaker(on: Boolean) { if (demo) demoSpeaker = on else Calls.speaker(on) }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // A real call always takes over from a pretend one.
        startDemo(intent)
    }

    private fun refreshNightByClock() {
        if (config.autoNight == AutoNight.CLOCK) {
            val now = Calendar.getInstance()
            night = NightMode.byClock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), config.nightFromHour, config.nightUntilHour)
        }
    }

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
        config = ConfigStore(this).load()
        refreshNightByClock()
        lightSensor = LightSensor(this) { lux -> if (config.autoNight == AutoNight.LIGHT) night = NightMode.byLight(lux, night) }
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

        startDemo(intent)
        setContent {
            val theme = if (night && config.autoNight != AutoNight.OFF) config.nightTheme else config.theme
            val style = remember(theme) { LvStyle(theme, AtkinsonFamily, config.textScale) }
            // Night colours also dim the window, like the home screen.
            LaunchedEffect(theme) {
                val attrs = window.attributes
                attrs.screenBrightness = theme.windowBrightness ?: config.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
            CompositionLocalProvider(LocalLv provides style, com.netgician.rplauncher.ui.LocalWindowFrame provides config.windowFrame) {
                val calls by (if (demo) demoCalls else Calls.list).collectAsState()
                val audio by Calls.audio.collectAsState()
                var lastName by remember { mutableStateOf("") }
                var lastSeconds by remember { mutableLongStateOf(0L) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    var ticks = 0
                    while (true) {
                        delay(1_000)
                        now = System.currentTimeMillis()
                        if (++ticks % 30 == 0) refreshNightByClock()
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
                        CallPhase.DIALING -> speaker.speak(tr("Calling %s", c.name))
                        else -> Unit
                    }
                }

                // Earpiece calls turn the screen off at the ear, so the cheek can't press buttons.
                val earpiece = !demo && main?.phase == CallPhase.ACTIVE && audio?.route != android.telecom.CallAudioState.ROUTE_SPEAKER
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
                            FitText(tr("Call ended"), style.fg, maxSp = 34f * style.textScale, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        CallScreen(
                            main, live, now,
                            if (demo) demoMuted else audio?.isMuted == true,
                            if (demo) demoSpeaker else audio?.route == android.telecom.CallAudioState.ROUTE_SPEAKER,
                        )
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
                if (keypad && typed.isNotEmpty()) typed else CallWords.status(main, now) + if (held != null) tr(" · %s on hold", held.name) else "",
                Modifier.fillMaxWidth().height((36f * lv.textScale).dp), say, maxSp = 22f,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    main.phase == CallPhase.RINGING && replies -> {
                        val choices = QuickReplies.forLanguage(Locale.getDefault().language).take(5)
                        Grid(listOf(tr("Back")) + choices, cols = 1, rows = 6) { item, m ->
                            if (item == tr("Back")) Tile(tr("Back"), m, onLongPress = { say(tr("Back")) }) { replies = false }
                            else Tile(item, m, onLongPress = { say(item) }) {
                                declineWithText(main.id, item)
                                say(if (demo) tr("Declined. Demo mode, so no text was sent.") else tr("Declined. Texting: %s", item))
                            }
                        }
                    }
                    main.phase == CallPhase.RINGING -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tile(
                                tr("Answer"), Modifier.fillMaxWidth().weight(2f), spoken = tr("Answer the call from %s", main.name),
                                icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true, onLongPress = { say(tr("Answer")) },
                            ) { answer(main.id) }
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Tile(
                                    tr("Decline"), Modifier.weight(1f).fillMaxHeight(), icon = TileIcon.Vector(Icons.Filled.Close),
                                    onLongPress = { say(tr("Decline")) },
                                ) { decline(main.id) }
                                Tile(
                                    tr("Text back"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Decline and send a text"),
                                    onLongPress = { say(tr("Decline and send a text")) },
                                ) { replies = true }
                            }
                        }
                    }
                    keypad -> {
                        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#", tr("Done"))
                        Grid(keys, cols = 3, rows = 5) { k, m ->
                            if (k == tr("Done")) Tile(tr("Done"), m, emphasized = true, onLongPress = { say(tr("Done")) }) {
                                keypad = false
                                typed = ""
                            } else Tile(k, m, spoken = CallWords.key(k), onLongPress = { say(CallWords.key(k)) }) {
                                key(main.id, k[0])
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
                                    tr("Hang up"), m, spoken = tr("Hang up"), icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true,
                                    onLongPress = { say(tr("Hang up")) },
                                ) { hangUp(main.id) }
                                "speaker" -> Tile(
                                    if (speakerOn) tr("Speaker on") else tr("Speaker"), m, emphasized = speakerOn,
                                    spoken = if (speakerOn) tr("Speaker is on. Tap to turn it off") else tr("Turn on the speaker"),
                                    onLongPress = { say(if (speakerOn) tr("Speaker is on") else tr("Speaker is off")) },
                                ) {
                                    setSpeaker(!speakerOn)
                                }
                                "mute" -> Tile(
                                    if (muted) tr("Muted") else tr("Mute"), m, emphasized = muted,
                                    spoken = if (muted) tr("You're muted. Tap to unmute") else tr("Mute your microphone"),
                                    onLongPress = { say(if (muted) tr("You're muted") else tr("Not muted")) },
                                ) {
                                    mute(!muted)
                                }
                                "louder" -> Tile(tr("Louder"), m, onLongPress = { say(tr("Louder")) }) {
                                    audio?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                }
                                "quieter" -> Tile(tr("Quieter"), m, onLongPress = { say(tr("Quieter")) }) {
                                    audio?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                }
                                "keypad" -> Tile(tr("Keypad"), m, spoken = tr("Keypad, for menus that ask you to press a number"), onLongPress = { say(tr("Keypad")) }) {
                                    keypad = true
                                }
                                else -> Tile(tr("Switch to %s", held?.name), m, onLongPress = { say(tr("Switch calls")) }) {
                                    held?.let { hold(it.id, false) }
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

    override fun onStart() {
        super.onStart()
        lightSensor?.start()
        refreshNightByClock()
    }

    override fun onStop() {
        super.onStop()
        lightSensor?.stop()
    }

    override fun onDestroy() {
        proximity(false)
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DEMO = "demo"
        private const val EXTRA_INCOMING = "incoming"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_NUMBER = "number"

        /** Demo mode's pretend call: ringing, or (for a call you make) dialling and then answered. */
        fun openDemo(context: Context, incoming: Boolean, name: String, number: String) {
            runCatching {
                context.startActivity(
                    android.content.Intent(context, CallActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_DEMO, true).putExtra(EXTRA_INCOMING, incoming)
                        .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number),
                )
            }
        }

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
