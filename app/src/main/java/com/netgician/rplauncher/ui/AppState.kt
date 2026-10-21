package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
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
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface Screen {
    data object Home : Screen
    data class WindowCheck(val returnTo: Screen) : Screen
    data object Apps : Screen
    data object See : Screen
    data class ConfirmCall(val contact: TileSpec.Contact) : Screen
    /** [next] is where a correct PIN leads: Setup, or one Setup screen. */
    data class Pin(val purpose: PinPurpose, val next: Screen = Settings) : Screen
    data object PinReset : Screen
    data object Settings : Screen
    /** Everything to set up, one line each. [firstRun] right after the very first window check. */
    data class Checklist(val firstRun: Boolean) : Screen
    data object EditTiles : Screen
    data object AddTile : Screen
    data object PickApp : Screen
    /** The Pages tile: every home page, to jump straight to one. */
    data object Pages : Screen
    /** Adding an app shortcut tile: first the app, then one of its shortcuts. */
    data object PickShortcutApp : Screen
    data class PickShortcut(val packageName: String, val appLabel: String) : Screen
    data object ReadText : Screen
    data object Alerts : Screen
    /** The Timer tile: how long, then the countdown. */
    data object Timer : Screen

    // Alarms and reminders: the list, one of them, then the steps of a new or changed one.
    data class AlarmList(val reminders: Boolean) : Screen
    data class AlarmEdit(val id: Long, val reminder: Boolean) : Screen
    data object AlarmMessage : Screen
    data object AlarmTime : Screen
    data object AlarmAmPm : Screen
    data object AlarmDays : Screen
    data object AlarmPickDays : Screen

    // Voice notes, the calculator, music and the light finder.
    data object Notes : Screen
    data object NoteRecord : Screen
    data class Note(val name: String) : Screen
    data object Calculator : Screen
    data object Music : Screen
    data object LightFinder : Screen

    // Texts
    data object Texts : Screen
    data class Conversation(val threadId: Long) : Screen
    /** One text on its own, from a conversation. [key] is "sms:12" or "mms:7". */
    data class Message(val threadId: Long, val key: String) : Screen
    data object Compose : Screen
    /** The less-used writing choices: photo, voice message, another person, read back, clear. */
    data object ComposeMore : Screen
    data object QuickReply : Screen
    data object NewText : Screen
    data class NumberPad(val purpose: NumberPurpose) : Screen
    data object AddPhoto : Screen
    data object Record : Screen
    data class Picture(val threadId: Long, val partIds: List<Long>, val index: Int) : Screen
    data class Card(val threadId: Long, val partId: Long) : Screen
    data class Invite(val threadId: Long, val partId: Long) : Screen
    /** Everything attached to one text, when there's more than one kind. */
    data class Attachments(val threadId: Long) : Screen
    data object TextsSwitch : Screen
    data object TextsCheck : Screen
    /** The Texts tile, before RP Launcher is the texting app: choose what Texts does. */
    data object TextsNotSet : Screen

    // Phone
    data object PhoneHome : Screen
    data class Dial(val number: String) : Screen
    data object Recents : Screen
    data object PhoneSwitch : Screen
    /** Voicemail: shows the number it will call before calling. */
    data object Voicemail : Screen
    data class VoicemailMessage(val id: Long) : Screen
    /** Setup: which number Voicemail calls. */
    data object VoicemailSetup : Screen

    // Safety
    data class Sos(val practice: Boolean) : Screen
    data object WhereAmI : Screen
    data object SosSetup : Screen
}

/** Why the number pad is open: a person to text, this phone's own number, the voicemail number, or a timer's minutes. */
enum class NumberPurpose { TEXT, OWN_NUMBER, VOICEMAIL, TIMER }

/** Whether night colours are on right now, for screens outside the home screen (the call screen). */
object NightNow {
    @Volatile var active: Boolean = false
}

/** Screens for helpers. These are never darkened and never use the person's layout mode. */
val Screen.isHelperScreen: Boolean
    get() = this is Screen.Settings || this is Screen.Checklist || this is Screen.Pin || this is Screen.PinReset ||
        this is Screen.EditTiles || this is Screen.AddTile || this is Screen.PickApp ||
        this is Screen.PickShortcutApp || this is Screen.PickShortcut ||
        this is Screen.WindowCheck || this is Screen.TextsSwitch || this is Screen.TextsCheck || this is Screen.SosSetup || this is Screen.PhoneSwitch ||
        this is Screen.VoicemailSetup || (this is Screen.NumberPad && purpose == NumberPurpose.VOICEMAIL) ||
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
        // The first start: the viewing window check, then the setup checklist.
        if (config.windowChecked) Screen.Home else Screen.WindowCheck(Screen.Checklist(firstRun = true)),
    )
        private set

    var homePage by mutableIntStateOf(0)

    /** The tile names on each home page, as last laid out (for the Pages tile). */
    var homePages by mutableStateOf<List<List<String>>>(emptyList())
    var listPage by mutableIntStateOf(0)

    /** Test builds only: the radius of the tunnel preview, as a share of the screen width; 0 for off. */
    var tunnel by mutableStateOf(0f)

    /** True for test builds, which have the developer aids. */
    val isTestBuild: Boolean
        get() = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** The last tile spoken in touch-to-hear mode; it gets a thicker outline. */
    var heard by mutableStateOf<String?>(null)

    /** A new or changed alarm or reminder, while its steps are chosen. */
    var alarmDraft by mutableStateOf<AlarmDraft?>(null)

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

    /** For reading phone state from screens. */
    val activityContext: android.content.Context get() = activity

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
        val before = config.tiles
        config = change(config)
        com.netgician.rplauncher.i18n.Lang.choose(config.language)
        store.save(config)
        refreshNightByClock()
        if (config.tiles != before && (config.tiles + before).any { it is TileSpec.Shortcut }) {
            val tiles = config.tiles
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.netgician.rplauncher.system.Shortcuts.keepPinned(activity, tiles) }
        }
    }

    /** Picks up a change saved elsewhere in the app (a shortcut tile added from another app). */
    fun reloadFromStore() {
        val fresh = store.load()
        if (fresh != config) {
            config = fresh
            refreshNightByClock()
        }
    }

    fun go(to: Screen) {
        val from = screen
        listPage = 0
        if ((from is Screen.Conversation || from is Screen.Message) && to !is Screen.Message) texts.stopPlaying()
        screen = to
        if (to == Screen.Home && from.isHelperScreen) announceMode()
    }

    /** Once a day at most: says when the viewing window check is due again. A helper runs it. */
    fun remindWindowCheck() {
        val now = System.currentTimeMillis()
        if (!config.windowCheckDue(now)) return
        val prefs = activity.getSharedPreferences("reminders", android.content.Context.MODE_PRIVATE)
        if (now - prefs.getLong("window", 0L) < 86_400_000L) return
        prefs.edit().putLong("window", now).apply()
        speaker.speak(tr("It's been a few months since the viewing window check. A helper can run it again in Setup."), flush = false)
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
        say(tr("Page %s of %s", page + 1, count))
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
            is Screen.Checklist -> go(if (s.firstRun) Screen.Home else Screen.Settings)
            Screen.AddTile -> go(Screen.EditTiles)
            Screen.PickApp -> go(Screen.AddTile)
            Screen.Pages -> go(Screen.Home)
            Screen.Timer -> go(Screen.Home)
            is Screen.AlarmList -> go(Screen.Home)
            is Screen.AlarmEdit -> go(Screen.AlarmList(s.reminder))
            Screen.AlarmMessage, Screen.AlarmTime, Screen.AlarmAmPm, Screen.AlarmDays, Screen.AlarmPickDays -> alarmBack(this, s)
            Screen.Notes, Screen.Calculator, Screen.Music, Screen.LightFinder -> go(Screen.Home)
            Screen.NoteRecord, is Screen.Note -> go(Screen.Notes)
            Screen.PickShortcutApp -> go(Screen.AddTile)
            is Screen.PickShortcut -> go(Screen.PickShortcutApp)
            Screen.Texts -> go(Screen.Home)
            is Screen.Conversation -> go(Screen.Texts)
            is Screen.Message -> go(Screen.Conversation(s.threadId))
            Screen.Compose -> {
                // Back keeps what was written; only Cancel throws it away.
                val thread = texts.draft?.threadId
                if (texts.draft?.let { it.text.isNotBlank() || it.files.isNotEmpty() } == true) say(tr("Your text is kept for now."))
                go(if (thread != null) Screen.Conversation(thread) else Screen.Texts)
            }
            Screen.QuickReply, Screen.AddPhoto, Screen.Record, Screen.ComposeMore -> go(Screen.Compose)
            is Screen.Attachments -> go(Screen.Conversation(s.threadId))
            Screen.NewText -> if (texts.draft?.to.isNullOrEmpty()) { texts.draft = null; go(Screen.Texts) } else go(Screen.Compose)
            is Screen.NumberPad -> go(
                when (s.purpose) {
                    NumberPurpose.OWN_NUMBER -> Screen.TextsCheck
                    NumberPurpose.VOICEMAIL -> Screen.VoicemailSetup
                    NumberPurpose.TIMER -> Screen.Timer
                    NumberPurpose.TEXT -> Screen.NewText
                },
            )
            is Screen.Picture -> go(Screen.Conversation(s.threadId))
            is Screen.Card -> go(Screen.Conversation(s.threadId))
            is Screen.Invite -> go(Screen.Conversation(s.threadId))
            Screen.TextsSwitch -> go(Screen.Settings)
            Screen.TextsNotSet -> go(Screen.Home)
            Screen.TextsCheck -> go(Screen.TextsSwitch)
            is Screen.Sos -> go(if (s.practice) Screen.SosSetup else Screen.Home)
            Screen.WhereAmI -> go(Screen.Home)
            Screen.SosSetup -> go(Screen.Settings)
            Screen.PhoneHome -> go(Screen.Home)
            is Screen.Dial, Screen.Recents -> go(if (Demo.phoneIsOurs(activity)) Screen.PhoneHome else Screen.Home)
            Screen.PhoneSwitch -> go(Screen.Settings)
            Screen.Voicemail -> go(Screen.PhoneHome)
            is Screen.VoicemailMessage -> go(Screen.Voicemail)
            Screen.VoicemailSetup -> go(Screen.Settings)
        }
    }

    fun start(intent: Intent, failMessage: String = tr("That app isn't available")) {
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
            TileSpec.Phone -> if (Demo.phoneIsOurs(activity)) go(Screen.PhoneHome) else start(Intent(Intent.ACTION_DIAL))
            TileSpec.Messages -> texts.openTile()
            TileSpec.Contacts -> start(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
            )
            TileSpec.Torch -> when (torch.toggle(config.torchStrength)) {
                true -> say(tr("Torch on"))
                false -> say(tr("Torch off"))
                null -> say(tr("This phone has no torch"))
            }
            TileSpec.See -> go(Screen.See)
            TileSpec.Alerts -> go(Screen.Alerts)
            TileSpec.Sos -> go(Screen.Sos(practice = false))
            TileSpec.WhereAmI -> go(Screen.WhereAmI)
            TileSpec.Wifi -> start(Intent(android.provider.Settings.Panel.ACTION_WIFI), tr("Could not open Wi-Fi"))
            TileSpec.Volume -> start(Intent(android.provider.Settings.Panel.ACTION_VOLUME), tr("Could not open volume"))
            TileSpec.Bluetooth -> start(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), tr("Could not open Bluetooth"))
            TileSpec.Notifications -> if (!com.netgician.rplauncher.system.StatusBar.notifications(activity)) {
                say(tr("This phone won't open the drop-down from here. Showing your alerts instead."))
                go(Screen.Alerts)
            }
            TileSpec.QuickSettings -> if (!com.netgician.rplauncher.system.StatusBar.quickSettings(activity)) {
                say(tr("This phone won't open quick settings from here. Opening settings instead."))
                start(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            TileSpec.AllApps -> go(Screen.Apps)
            TileSpec.Pages -> go(Screen.Pages)
            TileSpec.Alarms -> go(Screen.AlarmList(reminders = false))
            TileSpec.Reminders -> go(Screen.AlarmList(reminders = true))
            TileSpec.Clock -> say(clockSpoken(activity))
            TileSpec.Notes -> go(Screen.Notes)
            TileSpec.Calculator -> go(Screen.Calculator)
            TileSpec.Music -> go(Screen.Music)
            TileSpec.LightFinder -> go(Screen.LightFinder)
            TileSpec.Timer -> {
                // The tile says "Timer done" while it rings: one tap stops it.
                val t = com.netgician.rplauncher.system.Timer.load(activity)
                if (t.phase == com.netgician.rplauncher.model.TimerState.Phase.RINGING) {
                    com.netgician.rplauncher.system.Timer.stop(activity)
                    say(tr("Timer stopped"))
                } else go(Screen.Timer)
            }
            is TileSpec.Shortcut -> com.netgician.rplauncher.system.Shortcuts.start(activity, spec)?.let { say(it) }
            is TileSpec.App -> start(
                Apps.launchIntent(spec.packageName, spec.className),
                tr("%s isn't installed", spec.appLabel),
            )
            is TileSpec.Contact -> go(Screen.ConfirmCall(spec))
        }
    }

    fun openSetup() {
        go(if (config.pinHash != null) Screen.Pin(PinPurpose.Unlock) else Screen.Settings)
    }

    /**
     * Start over: RP Launcher's own setup back to how it came (tiles, window, colours, PIN, SOS
     * contacts), then the first-start window check and checklist. Texts, calls and Android's own
     * choices (permissions, default apps) are left as they are; the checklist shows each one.
     */
    fun startOver() {
        if (Demo.on) {
            Demo.set(false)
            texts.demoChanged()
        }
        update { LauncherConfig() }
        activity.getSharedPreferences("reminders", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        homePage = 0
        tunnel = 0f
        speaker.rate = config.speechRate
        speaker.pitch = config.speechPitch
        speaker.voiceName = config.voiceName
        go(Screen.WindowCheck(Screen.Checklist(firstRun = true)))
    }

    /** Clears a forgotten PIN and opens Setup so a new one can be chosen. */
    fun resetPin() {
        update { it.copy(pinHash = null) }
        say(tr("The helper PIN is removed. You can set a new one in Setup."))
        go(Screen.Settings)
    }

    fun exportTo(uri: Uri) {
        val ok = runCatching {
            activity.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(ConfigJson.encode(config).toByteArray())
            } != null
        }.getOrDefault(false)
        say(if (ok) tr("Setup saved") else tr("Could not save the setup"))
    }

    fun importFrom(uri: Uri) {
        val loaded = runCatching {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?.let { ConfigJson.decodeSetupFile(it) }
        }.getOrNull()
        if (loaded == null) {
            say(tr("That file is not an RP Launcher setup"))
        } else {
            update { loaded }
            homePage = 0
            say(tr("Setup loaded"))
        }
    }
}
