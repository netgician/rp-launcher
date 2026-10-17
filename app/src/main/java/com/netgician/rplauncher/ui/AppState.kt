package com.netgician.rplauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netgician.rplauncher.model.AimHelp
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.Hints
import com.netgician.rplauncher.model.Landmarks
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.LvTheme
import com.netgician.rplauncher.model.NightMode
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.ocr.OcrEngine
import com.netgician.rplauncher.ocr.OcrResult
import com.netgician.rplauncher.system.Apps
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Torch
import com.netgician.rplauncher.system.Vibes
import kotlinx.coroutines.CoroutineScope
import java.util.Calendar

sealed interface Screen {
    data object Home : Screen
    data class WindowCheck(val returnTo: Screen) : Screen
    data object Apps : Screen
    data object See : Screen
    data class ConfirmCall(val contact: TileSpec.Contact) : Screen
    data class Pin(val purpose: PinPurpose) : Screen
    data object PinReset : Screen
    data object Settings : Screen
    data object EditTiles : Screen
    data object AddTile : Screen
    data object PickApp : Screen
    data object ReadText : Screen
    data object Alerts : Screen

    // Texts
    data object Texts : Screen
    data class Conversation(val threadId: Long) : Screen
    data object Compose : Screen
    data object QuickReply : Screen
    data object NewText : Screen
    data class NumberPad(val purpose: NumberPurpose) : Screen
    data object AddPhoto : Screen
    data object Record : Screen
    data class Picture(val threadId: Long, val partIds: List<Long>, val index: Int) : Screen
    data class Card(val threadId: Long, val partId: Long) : Screen
    data class Invite(val threadId: Long, val partId: Long) : Screen
    data object TextsSwitch : Screen
    data object TextsCheck : Screen

    // Safety
    data class Sos(val practice: Boolean) : Screen
    data object WhereAmI : Screen
    data object SosSetup : Screen
}

/** Why the number pad is open: a person to text, or this phone's own number for Check texts. */
enum class NumberPurpose { TEXT, OWN_NUMBER }

/** Screens for helpers. These are never darkened and never use the person's layout mode. */
val Screen.isHelperScreen: Boolean
    get() = this is Screen.Settings || this is Screen.Pin || this is Screen.PinReset ||
        this is Screen.EditTiles || this is Screen.AddTile || this is Screen.PickApp ||
        this is Screen.WindowCheck || this is Screen.TextsSwitch || this is Screen.TextsCheck || this is Screen.SosSetup ||
        (this is Screen.NumberPad && purpose == NumberPurpose.OWN_NUMBER)

/**
 * Touch to hear (tap to hear, double tap to open) is for the person's own screens.
 * Helper screens such as Setup always act on a single tap, so they can never get stuck.
 * So does SOS: Cancel must never need a double tap.
 */
fun touchToHearOn(config: LauncherConfig, screenReaderOn: Boolean, screen: Screen): Boolean =
    config.touchToHear && !screenReaderOn && !screen.isHelperScreen && screen !is Screen.Sos

/** What the camera read: one line per page, the voice to use, any aiming advice, and where Back goes. */
data class ReadText(val lines: List<String>, val language: String, val hints: List<String>, val backTo: Screen = Screen.See)

enum class PinPurpose { Unlock, Set }

/** Everything the screens share: the saved setup, where we are, speech, vibration and the torch. */
class AppState(
    private val activity: Activity,
    private val store: ConfigStore,
    val speaker: Speaker,
    val torch: Torch,
    val vibes: Vibes,
    val scope: CoroutineScope,
) {
    var config by mutableStateOf(store.load())
        private set

    var screen by mutableStateOf<Screen>(
        if (config.windowChecked) Screen.Home else Screen.WindowCheck(Screen.Home),
    )
        private set

    var homePage by mutableIntStateOf(0)
    var listPage by mutableIntStateOf(0)

    /** The last tile spoken in touch-to-hear mode; it gets a thicker outline. */
    var heard by mutableStateOf<String?>(null)

    /** The latest text read from the camera. */
    var readText by mutableStateOf<ReadText?>(null)
        private set

    private var engine: OcrEngine? = null

    /** The text reader, loaded on first use (about a second). Call off the main thread. */
    @Synchronized
    fun ocr(): OcrEngine = engine ?: OcrEngine.fromResources(threads = 2).also { engine = it }

    fun closeOcr() {
        engine?.close()
        engine = null
    }

    /** Texts: what's being written, played and checked. */
    val texts = TextsState(this, activity, scope)

    /** Shows what the camera read and speaks it: advice first, then the text in its own voice. */
    fun showRead(result: OcrResult, backTo: Screen = Screen.See) {
        val lines = result.lines.map { it.text }
        val language = Language.guess(result.text)
        val hints = AimHelp.hints(result.lines.flatMap { it.boxes }, result.imageWidth, result.imageHeight)
        readText = ReadText(lines, language, hints, backTo)
        go(Screen.ReadText)
        hints.forEach { speaker.speak(it, flush = false) }
        if (lines.isNotEmpty()) speaker.speak(lines.joinToString(". "), language, flush = hints.isEmpty())
    }

    /** True while automatic night colours are on. */
    var nightActive by mutableStateOf(false)
        private set

    /** The colours in use right now: the night theme at night, otherwise the chosen theme. */
    val theme: LvTheme
        get() = if (nightActive && config.autoNight != AutoNight.OFF) config.nightTheme else config.theme

    /** Speech first with the dark screen on, outside the helper screens. */
    val darkScreenNow: Boolean
        get() = config.visionMode == VisionMode.SPEECH && config.darkScreen && !screen.isHelperScreen

    /** The launcher window's brightness: lowest under the dark screen, the theme's own, else the Setup limit. */
    val windowBrightness: Float?
        get() = if (darkScreenNow) 0.01f else theme.windowBrightness ?: config.brightness

    fun update(change: (LauncherConfig) -> LauncherConfig) {
        config = change(config)
        store.save(config)
        refreshNightByClock()
    }

    fun go(to: Screen) {
        val from = screen
        listPage = 0
        if (from is Screen.Conversation && to !is Screen.Conversation) texts.stopPlaying()
        screen = to
        if (to == Screen.Home && from.isHelperScreen) announceMode()
    }

    /** In a speech mode with hints on, says how the gestures work. */
    fun announceMode() {
        if (config.spokenHints && touchToHearOn(config, speaker.screenReaderOn, Screen.Home)) {
            speaker.speak(Hints.modeAnnouncement(config.visionMode))
        }
    }

    fun say(text: String) = speaker.speak(text)

    /** Plays a vibration pattern, if the person has touch landmarks turned on. */
    fun feel(pattern: LongArray) {
        if (config.touchLandmarks) vibes.play(pattern)
    }

    fun onPageTurned(page: Int, count: Int) {
        feel(Landmarks.PAGE_TURN)
        say("Page ${page + 1} of $count")
    }

    /** Called every minute and whenever the setup changes. */
    fun refreshNightByClock() {
        when (config.autoNight) {
            AutoNight.OFF -> nightActive = false
            AutoNight.CLOCK -> {
                val now = Calendar.getInstance()
                nightActive = NightMode.byClock(
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    config.nightFromHour,
                    config.nightUntilHour,
                )
            }
            AutoNight.LIGHT -> Unit // decided by onLux
        }
    }

    fun onLux(lux: Float) {
        if (config.autoNight == AutoNight.LIGHT) nightActive = NightMode.byLight(lux, nightActive)
    }

    /** The Home button: first page of the home screen, unless the first-run check is still open. */
    fun onHomePressed() {
        if (!config.windowChecked) return
        if (screen == Screen.Home) homePage = 0 else go(Screen.Home)
    }

    /** The Back button never leaves the launcher; it steps back one screen. */
    fun back() {
        when (val s = screen) {
            Screen.Home -> if (homePage > 0) homePage--
            is Screen.WindowCheck -> if (config.windowChecked) go(s.returnTo)
            is Screen.Pin -> go(if (s.purpose == PinPurpose.Set) Screen.Settings else Screen.Home)
            Screen.PinReset -> go(Screen.Pin(PinPurpose.Unlock))
            Screen.Apps, Screen.See, Screen.Alerts, is Screen.ConfirmCall, Screen.Settings -> go(Screen.Home)
            Screen.ReadText -> go(readText?.backTo ?: Screen.See)
            Screen.EditTiles -> go(Screen.Settings)
            Screen.AddTile -> go(Screen.EditTiles)
            Screen.PickApp -> go(Screen.AddTile)
            Screen.Texts -> go(Screen.Home)
            is Screen.Conversation -> go(Screen.Texts)
            Screen.Compose -> {
                // Back keeps what was written; only Cancel throws it away.
                val thread = texts.draft?.threadId
                if (texts.draft?.let { it.text.isNotBlank() || it.files.isNotEmpty() } == true) say("Your text is kept for now.")
                go(if (thread != null) Screen.Conversation(thread) else Screen.Texts)
            }
            Screen.QuickReply, Screen.AddPhoto, Screen.Record -> go(Screen.Compose)
            Screen.NewText -> if (texts.draft?.to.isNullOrEmpty()) { texts.draft = null; go(Screen.Texts) } else go(Screen.Compose)
            is Screen.NumberPad -> go(if (s.purpose == NumberPurpose.OWN_NUMBER) Screen.TextsCheck else Screen.NewText)
            is Screen.Picture -> go(Screen.Conversation(s.threadId))
            is Screen.Card -> go(Screen.Conversation(s.threadId))
            is Screen.Invite -> go(Screen.Conversation(s.threadId))
            Screen.TextsSwitch -> go(Screen.Settings)
            Screen.TextsCheck -> go(Screen.TextsSwitch)
            is Screen.Sos -> go(if (s.practice) Screen.SosSetup else Screen.Home)
            Screen.WhereAmI -> go(Screen.Home)
            Screen.SosSetup -> go(Screen.Settings)
        }
    }

    fun start(intent: Intent, failMessage: String = "That app isn't available") {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            say(failMessage)
        } catch (e: SecurityException) {
            say(failMessage)
        }
    }

    fun open(spec: TileSpec) {
        when (spec) {
            TileSpec.Phone -> start(Intent(Intent.ACTION_DIAL))
            TileSpec.Messages -> texts.openTile()
            TileSpec.Contacts -> start(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
            )
            TileSpec.Torch -> when (torch.toggle()) {
                true -> say("Torch on")
                false -> say("Torch off")
                null -> say("This phone has no torch")
            }
            TileSpec.See -> go(Screen.See)
            TileSpec.Alerts -> go(Screen.Alerts)
            TileSpec.Sos -> go(Screen.Sos(practice = false))
            TileSpec.WhereAmI -> go(Screen.WhereAmI)
            TileSpec.AllApps -> go(Screen.Apps)
            is TileSpec.App -> start(
                Apps.launchIntent(spec.packageName, spec.className),
                "${spec.appLabel} isn't installed",
            )
            is TileSpec.Contact -> go(Screen.ConfirmCall(spec))
        }
    }

    fun openSetup() {
        go(if (config.pinHash != null) Screen.Pin(PinPurpose.Unlock) else Screen.Settings)
    }

    /** Clears a forgotten PIN and opens Setup so a new one can be chosen. */
    fun resetPin() {
        update { it.copy(pinHash = null) }
        say("The helper PIN is removed. You can set a new one in Setup.")
        go(Screen.Settings)
    }

    fun exportTo(uri: Uri) {
        val ok = runCatching {
            activity.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(ConfigJson.encode(config).toByteArray())
            } != null
        }.getOrDefault(false)
        say(if (ok) "Setup saved" else "Could not save the setup")
    }

    fun importFrom(uri: Uri) {
        val loaded = runCatching {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?.let { ConfigJson.decodeSetupFile(it) }
        }.getOrNull()
        if (loaded == null) {
            say("That file is not an RP Launcher setup")
        } else {
            update { loaded }
            homePage = 0
            say("Setup loaded")
        }
    }
}
