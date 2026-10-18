package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.Hints
import com.netgician.rplauncher.model.Landmarks
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Pin
import com.netgician.rplauncher.model.Themes
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.VisionMode
import com.netgician.rplauncher.model.after
import com.netgician.rplauncher.system.AlertsStore
import com.netgician.rplauncher.system.ScreenLock
import kotlin.math.roundToInt

/** A scrolling column of big rows, inside the viewing window. Used by the helper screens. */
@Composable
internal fun HelperColumn(state: AppState, content: @Composable ColumnScope.() -> Unit) {
    InWindow(state.config.window) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val context = LocalContext.current
    val c = state.config
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) state.exportTo(uri)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importFrom(uri)
    }
    val homeRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(Unit) { state.say(tr("Setup")) }

    // Start over needs a second tap, so it can't happen by accident.
    var startOverArmed by remember { mutableStateOf(false) }
    LaunchedEffect(startOverArmed) {
        if (startOverArmed) {
            kotlinx.coroutines.delay(8_000)
            startOverArmed = false
        }
    }

    HelperColumn(state) {
        SettingRow(tr("Done")) { state.go(Screen.Home) }
        SettingRow(tr("Setup checklist (everything in one list)")) { state.go(Screen.Checklist(firstRun = false)) }

        // The language of RP Launcher's own words. Texts, names and app names are never translated.
        val phoneLanguage = if (java.util.Locale.getDefault().language == "es") "Español" else "English"
        SettingRow(
            tr("Language"),
            when (c.language) {
                "en" -> "English"
                "es" -> "Español"
                else -> tr("the phone's (%s)", phoneLanguage)
            },
        ) {
            // The phone's language, then Spanish, then English, then back.
            val next = when (c.language) {
                null -> "es"
                "es" -> "en"
                else -> null
            }
            state.update { it.copy(language = next) }
            state.speaker.stop()
            state.say(
                when (next) {
                    "es" -> "Español"
                    "en" -> "English"
                    else -> tr("The phone's language")
                },
            )
        }
        SettingRow(tr("Vision mode"), c.visionMode.label) {
            val next = VisionMode.entries.after(c.visionMode)
            // Speech modes turn on touch landmarks too; the person can still turn them off.
            state.update { it.copy(visionMode = next, touchLandmarks = it.touchLandmarks || next != VisionMode.SIGHT) }
            state.heard = null
            val talkBack = if (state.speaker.screenReaderOn) tr(" TalkBack is on, so tiles work the TalkBack way.") else ""
            val setup = if (next == VisionMode.SIGHT) "" else tr(" Setup still works with a single tap.")
            state.say("${next.label}. ${next.explain}$setup$talkBack")
        }
        if (c.visionMode != VisionMode.SIGHT) {
            SettingRow(tr("Spoken hints"), if (c.spokenHints) tr("On") else tr("Off")) {
                val on = !c.spokenHints
                state.update { it.copy(spokenHints = on) }
                state.say(if (on) tr("Spoken hints on. %s", Hints.TOUCH) else tr("Spoken hints off"))
            }
        }
        if (c.visionMode == VisionMode.SPEECH) {
            SettingRow(tr("Dark screen"), if (c.darkScreen) tr("On") else tr("Off")) {
                val on = !c.darkScreen
                state.update { it.copy(darkScreen = on) }
                state.say(
                    if (on) tr("Dark screen on. Tiles still work by touch and speech. Setup is never darkened.")
                    else tr("Dark screen off"),
                )
            }
        }
        SettingRow(tr("Alerts access"), if (AlertsStore.hasAccess(context)) tr("On") else tr("Off")) {
            state.start(AlertsStore.accessIntent(context), tr("Could not open the setting"))
        }
        SettingRow(tr("Colours"), tr(c.theme.name)) {
            val next = Themes.next(c.theme)
            state.update { it.copy(themeId = next.id) }
            state.say(tr(next.name))
        }
        SettingRow(tr("Columns"), "${c.cols}") {
            val next = LauncherConfig.COLUMN_CHOICES.after(c.cols)
            state.update { it.copy(cols = next) }
            state.say(tr("%s columns", next))
        }
        SettingRow(tr("Rows"), "${c.rows}") {
            val next = LauncherConfig.ROW_CHOICES.after(c.rows)
            state.update { it.copy(rows = next) }
            state.say(tr("%s rows", next))
        }
        val size = LauncherConfig.TEXT_SIZES.firstOrNull { it.scale == c.textScale } ?: LauncherConfig.TEXT_SIZES[1]
        SettingRow(tr("Text size"), size.name) {
            val next = LauncherConfig.TEXT_SIZES.after(size)
            state.update { it.copy(textScale = next.scale) }
            state.say(tr("%s text", next.name))
        }
        SettingRow(tr("Speak on long press"), if (c.speakOnLongPress) tr("On") else tr("Off")) {
            val on = !c.speakOnLongPress
            state.update { it.copy(speakOnLongPress = on) }
            state.say(if (on) tr("Long press speaks") else tr("Long press is silent"))
        }
        val speed = LauncherConfig.SPEECH_SPEEDS.firstOrNull { it.rate == c.speechRate } ?: LauncherConfig.SPEECH_SPEEDS[1]
        SettingRow(tr("Speech speed"), speed.name) {
            val next = LauncherConfig.SPEECH_SPEEDS.after(speed)
            state.update { it.copy(speechRate = next.rate) }
            state.speaker.rate = next.rate
            state.say(tr("%s speech", next.name))
        }
        val pitch = LauncherConfig.VOICE_PITCHES.firstOrNull { it.pitch == c.speechPitch } ?: LauncherConfig.VOICE_PITCHES[0]
        SettingRow(tr("Voice pitch"), pitch.name) {
            val next = LauncherConfig.VOICE_PITCHES.after(pitch)
            state.update { it.copy(speechPitch = next.pitch) }
            state.speaker.pitch = next.pitch
            state.say(tr("%s voice", next.name))
        }
        val voices = remember { state.speaker.voices() }
        if (voices.size > 1) {
            val current = voices.indexOfFirst { it.name == c.voiceName }
            SettingRow(tr("Voice"), if (current < 0) tr("Phone default") else tr("Voice %s of %s", current + 1, voices.size)) {
                // Cycles: phone default, then each installed voice.
                val next = if (current + 1 >= voices.size) null else voices[current + 1].name
                state.update { it.copy(voiceName = next) }
                state.speaker.voiceName = next
                state.say(if (next == null) tr("Phone default voice") else tr("Voice %s of %s. This is how it sounds.", current + 2, voices.size))
            }
        }
        if (state.torch.dimmable) {
            val level = LauncherConfig.TORCH_LEVELS.firstOrNull { it.strength == c.torchStrength } ?: LauncherConfig.TORCH_LEVELS[0]
            SettingRow(tr("Torch brightness"), level.name) {
                val next = LauncherConfig.TORCH_LEVELS.after(level)
                state.update { it.copy(torchStrength = next.strength) }
                state.torch.setStrength(next.strength)
                state.say(tr("Torch brightness %s", next.name))
            }
        }
        SettingRow(tr("Touch landmarks"), if (c.touchLandmarks) tr("On") else tr("Off")) {
            val on = !c.touchLandmarks
            state.update { it.copy(touchLandmarks = on) }
            if (on) state.vibes.play(Landmarks.rowPattern(2))
            state.say(if (on) tr("Touch landmarks on. A long press buzzes once for each row") else tr("Touch landmarks off"))
        }
        SettingRow(tr("Automatic night colours"), c.autoNight.label) {
            val next = AutoNight.entries.after(c.autoNight)
            state.update { it.copy(autoNight = next) }
            state.say(tr("Automatic night colours: %s", next.label))
        }
        SettingRow(tr("Night colours"), tr(c.nightTheme.name)) {
            val next = LauncherConfig.NIGHT_THEMES.after(c.nightTheme)
            state.update { it.copy(nightThemeId = next.id) }
            state.say(tr(next.name))
        }
        if (c.autoNight == AutoNight.CLOCK) {
            SettingRow(tr("Night from"), hourLabel(context, c.nightFromHour)) {
                val next = LauncherConfig.NIGHT_FROM_HOURS.after(c.nightFromHour)
                state.update { it.copy(nightFromHour = next) }
                state.say(tr("Night from %s", hourLabel(context, next)))
            }
            SettingRow(tr("Night until"), hourLabel(context, c.nightUntilHour)) {
                val next = LauncherConfig.NIGHT_UNTIL_HOURS.after(c.nightUntilHour)
                state.update { it.copy(nightUntilHour = next) }
                state.say(tr("Night until %s", hourLabel(context, next)))
            }
        }
        SettingRow(tr("Launcher brightness"), brightnessLabel(c.brightness)) {
            val next = LauncherConfig.BRIGHTNESS_CHOICES.after(c.brightness)
            state.update { it.copy(brightness = next) }
            state.say(tr("Launcher brightness: %s", brightnessLabel(next)))
        }
        SettingRow(tr("Viewing window check"), if (c.windowCheckDue(System.currentTimeMillis())) tr("due") else null) {
            state.go(Screen.WindowCheck(Screen.Settings))
        }
        SettingRow(tr("Frame around the window"), if (c.windowFrame) tr("on") else tr("off")) {
            val next = !c.windowFrame
            state.update { it.copy(windowFrame = next) }
            state.say(if (next) tr("Frame on. A bright line now shows the edges of your viewing window.") else tr("Frame off"))
        }
        SettingRow(tr("Window check reminder"), if (c.windowReminderMonths == 0) tr("Off") else tr("Every %s months", c.windowReminderMonths)) {
            val next = LauncherConfig.WINDOW_REMINDERS.after(c.windowReminderMonths)
            state.update {
                it.copy(windowReminderMonths = next, windowCheckedAt = if (it.windowCheckedAt == 0L) System.currentTimeMillis() else it.windowCheckedAt)
            }
            state.say(if (next == 0) tr("Reminder off") else tr("A reminder to re-check the viewing window every %s months", next))
        }
        SettingRow(tr("Edit tiles")) { state.go(Screen.EditTiles) }
        SettingRow(if (c.pinHash == null) tr("Set a helper PIN") else tr("Change the helper PIN")) {
            state.go(Screen.Pin(PinPurpose.Set))
        }
        if (c.pinHash != null) {
            SettingRow(tr("Remove the helper PIN")) {
                state.update { it.copy(pinHash = null) }
                state.say(tr("PIN removed"))
            }
        }
        SettingRow(tr("SOS"), if (c.sosContacts.isEmpty()) tr("not set up") else tr("%s contacts", c.sosContacts.size)) {
            state.go(Screen.SosSetup)
        }
        SettingRow(tr("Phone app"), com.netgician.rplauncher.calls.PhoneRole.currentAppName(context) ?: tr("none set")) {
            state.go(Screen.PhoneSwitch)
        }
        SettingRow(tr("Texting app"), com.netgician.rplauncher.texts.TextRole.currentAppName(context) ?: tr("none set")) {
            state.go(Screen.TextsSwitch)
        }
        SettingRow(tr("Voicemail number"), c.voicemailNumber?.let { com.netgician.rplauncher.calls.CallWords.grouped(it) } ?: tr("the phone's own")) {
            state.go(Screen.VoicemailSetup)
        }
        SettingRow(tr("Make RP Launcher the home screen")) {
            requestHomeRole(context, state) { homeRole.launch(it) }
        }
        SettingRow(tr("Save setup to a file")) { exportFile.launch("rp-launcher-setup.json") }
        SettingRow(tr("Load setup from a file")) {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        SettingRow(tr("Demo mode (sample texts and calls)"), if (Demo.on) tr("on") else tr("off")) {
            Demo.set(!Demo.on)
            state.texts.demoChanged()
            state.say(
                if (Demo.on) tr("Demo mode on. Texts and Phone show made-up people, and no texts are sent. It turns off by itself in two hours.")
                else tr("Demo mode off"),
            )
        }
        if (Demo.on) {
            SettingRow(tr("Show a sample incoming call")) {
                com.netgician.rplauncher.calls.CallActivity.openDemo(context, incoming = true, name = Demo.CALLER_NAME, number = Demo.CALLER_NUMBER)
            }
        }
        if (state.isTestBuild) {
            val tunnels = listOf(0f, 0.4f, 0.25f, 0.15f)
            val names = listOf(tr("Off"), tr("Wide"), tr("Narrow"), tr("Very narrow"))
            val i = tunnels.indexOf(state.tunnel).coerceAtLeast(0)
            SettingRow(tr("Tunnel preview (test builds)"), names[i]) {
                val next = (i + 1) % tunnels.size
                state.tunnel = tunnels[next]
                state.say(tr("Tunnel preview %s", names[next]))
            }
        }
        SettingRow(if (startOverArmed) tr("Tap again to erase the setup") else tr("Start over (erase this setup)")) {
            if (!startOverArmed) {
                startOverArmed = true
                state.say(
                    tr("This erases the tiles, colours, viewing window, PIN and SOS contacts, and starts again from the window check. Texts and calls are kept. Tap again to start over."),
                )
            } else {
                startOverArmed = false
                state.say(tr("Starting over"))
                state.startOver()
            }
        }
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        SettingRow(tr("Version"), version ?: tr("unknown")) { state.say(tr("RP Launcher version %s", version ?: tr("unknown"))) }
    }
}

private fun hourLabel(context: Context, hour: Int): String =
    if (DateFormat.is24HourFormat(context)) "%02d:00".format(hour)
    else {
        val h = if (hour % 12 == 0) 12 else hour % 12
        "$h ${if (hour < 12) tr("AM") else tr("PM")}"
    }

private fun brightnessLabel(b: Float?): String =
    if (b == null) tr("Phone setting") else "${(b * 100).roundToInt()}%"

private fun requestHomeRole(context: Context, state: AppState, launch: (Intent) -> Unit) {
    val roles = context.getSystemService(RoleManager::class.java)
    if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            state.say(tr("RP Launcher is already the home screen"))
        } else {
            launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        }
    } else {
        state.start(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
    }
}

/** A big keypad for the helper PIN. Digits are never spoken aloud. */
@Composable
fun PinScreen(state: AppState, purpose: PinPurpose) {
    var entered by remember(purpose) { mutableStateOf("") }
    var message by remember(purpose) {
        mutableStateOf(if (purpose == PinPurpose.Unlock) tr("Helper PIN") else tr("Choose a PIN"))
    }
    LaunchedEffect(purpose) {
        state.say(
            if (purpose == PinPurpose.Unlock) tr("Enter the helper PIN")
            else tr("Choose a PIN of at least %s digits", Pin.MIN_LENGTH),
        )
    }
    val context = LocalContext.current
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "ok") +
        (if (purpose == PinPurpose.Unlock) listOf("forgot") else emptyList())
    InWindow(state.config.window) {
        PagedTiles(
            items = keys,
            page = 0,
            onPage = { _, _ -> },
            cols = 3,
            rows = if (purpose == PinPurpose.Unlock) 5 else 4,
            onSpeak = state::say,
            header = { m ->
                val shown = if (entered.isEmpty()) message else "• ".repeat(entered.length).trim()
                SpokenText(shown, m, state::say, spoken = tr("%s. %s digits entered", message, entered.length))
            },
            minTarget = 0f,
        ) { key, m, _ ->
            when (key) {
                "forgot" -> Tile(tr("Forgot PIN"), m, spoken = tr("Forgot the PIN? Reset it")) {
                    if (ScreenLock.isSet(context)) {
                        ScreenLock.confirm(
                            context,
                            onSuccess = { state.resetPin() },
                            onFailure = { state.say(tr("The PIN was not reset")) },
                        )
                    } else {
                        state.go(Screen.PinReset)
                    }
                }
                "clear" -> if (entered.isEmpty()) {
                    Tile(tr("Cancel"), m) { state.back() }
                } else {
                    Tile(tr("Clear"), m, spoken = tr("Clear the digits")) { entered = "" }
                }
                "ok" -> Tile(tr("OK"), m, emphasized = true) {
                    when (purpose) {
                        PinPurpose.Unlock -> if (Pin.matches(entered, state.config.pinHash)) {
                            state.go((state.screen as? Screen.Pin)?.next ?: Screen.Settings)
                        } else {
                            entered = ""
                            message = tr("Wrong PIN. Try again")
                            state.say(message)
                        }
                        PinPurpose.Set -> if (entered.length >= Pin.MIN_LENGTH) {
                            val hash = Pin.hash(entered)
                            state.update { it.copy(pinHash = hash) }
                            state.say(tr("PIN saved"))
                            state.go(Screen.Settings)
                        } else {
                            message = tr("Use at least %s digits", Pin.MIN_LENGTH)
                            state.say(message)
                        }
                    }
                }
                else -> Tile(key, m) { if (entered.length < 8) entered += key }
            }
        }
    }
}

@Composable
fun EditTilesScreen(state: AppState) {
    val lv = LocalLv.current
    val tiles = state.config.tiles
    LaunchedEffect(Unit) { state.say(tr("Edit tiles")) }
    HelperColumn(state) {
        SettingRow(tr("Back to setup")) { state.go(Screen.Settings) }
        SettingRow(tr("Add a tile")) { state.go(Screen.AddTile) }
        tiles.forEachIndexed { index, spec ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(2.dp, lv.accent), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().semantics { contentDescription = tr("Tile %s, %s", index + 1, spec.label) }) {
                    FitText(
                        text = "${index + 1}. ${spec.label}",
                        color = lv.fg,
                        maxSp = 24f * lv.textScale,
                        align = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height((64f * lv.textScale).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tile(tr("Up"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Move %s up", spec.label)) {
                        move(state, index, -1)
                    }
                    Tile(tr("Down"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Move %s down", spec.label)) {
                        move(state, index, 1)
                    }
                    Tile(tr("Remove"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Remove %s", spec.label)) {
                        state.update { c -> c.copy(tiles = c.tiles.filterIndexed { i, _ -> i != index }) }
                        state.say(tr("%s removed", spec.label))
                    }
                }
            }
        }
    }
}

private fun move(state: AppState, index: Int, delta: Int) {
    val tiles = state.config.tiles
    val target = index + delta
    if (index !in tiles.indices || target !in tiles.indices) {
        state.say(if (delta < 0) tr("Already first") else tr("Already last"))
        return
    }
    val moved = tiles.toMutableList().apply { add(target, removeAt(index)) }
    state.update { it.copy(tiles = moved) }
    state.say(tr("%s is now number %s", tiles[index].label, target + 1))
}

@Composable
fun AddTileScreen(state: AppState) {
    val context = LocalContext.current
    fun add(spec: TileSpec) {
        state.update { it.copy(tiles = it.tiles + spec) }
        state.say(tr("%s added", spec.label))
        state.go(Screen.EditTiles)
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val contact = readPhoneContact(context, uri)
        if (contact == null) state.say(tr("Could not read that contact")) else add(contact)
    }
    LaunchedEffect(Unit) { state.say(tr("Add a tile")) }
    HelperColumn(state) {
        SettingRow(tr("Back")) { state.go(Screen.EditTiles) }
        SettingRow(tr("An app")) { state.go(Screen.PickApp) }
        SettingRow(tr("A person to call")) {
            runCatching {
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }.onFailure { state.say(tr("No contacts app found")) }
        }
        SettingRow(tr("Phone")) { add(TileSpec.Phone) }
        SettingRow(tr("Texts")) { add(TileSpec.Messages) }
        SettingRow(tr("Contacts")) { add(TileSpec.Contacts) }
        SettingRow(tr("Torch")) { add(TileSpec.Torch) }
        SettingRow(tr("See (camera magnifier)")) { add(TileSpec.See) }
        SettingRow(tr("Alerts (notifications read aloud)")) { add(TileSpec.Alerts) }
        SettingRow(tr("SOS (emergency texts)")) { add(TileSpec.Sos) }
        SettingRow(tr("Where am I (your street address)")) { add(TileSpec.WhereAmI) }
        SettingRow(tr("Notifications (the drop-down)")) { add(TileSpec.Notifications) }
        SettingRow(tr("Quick settings (the drop-down)")) { add(TileSpec.QuickSettings) }
        SettingRow(tr("Wi-Fi")) { add(TileSpec.Wifi) }
        SettingRow(tr("Bluetooth")) { add(TileSpec.Bluetooth) }
        SettingRow(tr("Volume")) { add(TileSpec.Volume) }
        // Sighted-help and reading apps, when they're installed.
        val helpers by produceState(initialValue = emptyList<com.netgician.rplauncher.system.AppEntry>()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.netgician.rplauncher.system.Apps.load(context).filter { a ->
                    HELPER_APPS.any { a.label.contains(it, ignoreCase = true) }
                }
            }
        }
        helpers.forEach { app ->
            SettingRow(tr("%s (helper app)", app.label)) { add(TileSpec.App(app.packageName, app.className, app.label)) }
        }
        SettingRow(tr("An app's shortcut (like Navigate home)")) {
            if (com.netgician.rplauncher.system.Shortcuts.allowed(context)) state.go(Screen.PickShortcutApp)
            else state.say(tr("App shortcuts only work while RP Launcher is the home screen. Make it the home screen in Setup first."))
        }
        SettingRow(tr("Pages (jump to any page)")) { add(TileSpec.Pages) }
        SettingRow(tr("All apps")) { add(TileSpec.AllApps) }
    }
}

/** An app's shortcuts, to add one as a tile. */
@Composable
fun PickShortcutScreen(state: AppState, packageName: String, appLabel: String) {
    val context = LocalContext.current
    val list by produceState<List<com.netgician.rplauncher.system.Shortcuts.Entry>?>(null, packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.netgician.rplauncher.system.Shortcuts.of(context, packageName)
        }
    }
    LaunchedEffect(list) {
        when {
            list == null -> Unit
            list!!.isEmpty() -> state.say(tr("%s has no shortcuts", appLabel))
            else -> state.say(tr("Shortcuts in %s", appLabel))
        }
    }
    HelperColumn(state) {
        SettingRow(tr("Back")) { state.go(Screen.PickShortcutApp) }
        when {
            list == null -> SettingRow(tr("Looking…")) { }
            list!!.isEmpty() -> SettingRow(tr("%s has no shortcuts", appLabel)) { state.go(Screen.PickShortcutApp) }
            else -> list!!.forEach { s ->
                SettingRow(s.longLabel) {
                    // The app's name too, so "New chat" on the home screen says whose it is.
                    val label = if (s.label.contains(appLabel, ignoreCase = true)) s.label else "${s.label} ($appLabel)"
                    state.update { it.copy(tiles = it.tiles + TileSpec.Shortcut(s.packageName, s.id, label)) }
                    state.say(tr("%s added", label))
                    state.go(Screen.EditTiles)
                }
            }
        }
    }
}

/** Apps that help people with low vision: sighted volunteers, AI descriptions, live agents. */
private val HELPER_APPS = listOf("Be My Eyes", "Lookout", "Aira", "Seeing AI", "Envision")

/** Reads the picked phone number. The picker grants access to that one row, so no contacts permission. */
internal fun readPhoneContact(context: Context, uri: Uri): TileSpec.Contact? = runCatching {
    val columns = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val number = c.getString(1)
        if (number.isNullOrBlank()) return@use null
        val name = c.getString(0)
        TileSpec.Contact(if (name.isNullOrBlank()) number else name, number)
    }
}.getOrNull()

/**
 * For phones with no screen lock: holding a tile for three seconds removes the PIN.
 * The hold keeps it from happening by accident, which is all the PIN is for.
 */
@Composable
fun PinResetScreen(state: AppState) {
    val lv = LocalLv.current
    val explain = tr("This phone has no screen lock. To remove the helper PIN, hold the Remove PIN tile for three seconds. To keep it, tap Back.")
    LaunchedEffect(Unit) { state.say(explain) }
    HelperColumn(state) {
        Box(Modifier.fillMaxWidth().semantics { contentDescription = explain }) {
            FitText(explain, lv.fg, maxSp = 22f * lv.textScale, maxLines = 6, modifier = Modifier.fillMaxWidth())
        }
        HoldTile(
            label = tr("Remove PIN (hold)"),
            seconds = 3,
            modifier = Modifier.fillMaxWidth().height((96f * lv.textScale).dp),
            onHeld = { state.resetPin() },
        )
        SettingRow(tr("Back")) { state.back() }
    }
}

/** A tile that only acts after being held down for [seconds]. */
@Composable
fun HoldTile(label: String, seconds: Int, modifier: Modifier, onHeld: () -> Unit) {
    val lv = LocalLv.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .border(BorderStroke(8.dp, lv.accent), shape)
            .background(lv.tile, shape)
            .semantics { contentDescription = tr("%s, hold for %s seconds", label, seconds) }
            .pointerInput(seconds) {
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(seconds * 1000L) { waitForUpOrCancellation() }
                    if (released == null) onHeld()
                }
            }
            .padding(10.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        FitText(label, lv.fg, maxSp = 26f * lv.textScale, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Shown instead of a black phone when the launcher failed to start twice in a row.
 * Uses only defaults, never the saved setup, in case that is what broke.
 */
@Composable
fun SafeScreen(start: (Intent) -> Unit, onTryAgain: () -> Unit, onResetTiles: () -> Unit) {
    val lv = LocalLv.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val notice = tr("RP Launcher had trouble starting. These buttons still work.")
        Box(Modifier.fillMaxWidth().semantics { contentDescription = notice }) {
            FitText(notice, lv.fg, maxSp = 26f, maxLines = 3, modifier = Modifier.fillMaxWidth())
        }
        SettingRow(tr("Phone")) { start(Intent(Intent.ACTION_DIAL)) }
        SettingRow(tr("Contacts")) {
            start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS))
        }
        SettingRow(tr("Try again")) { onTryAgain() }
        SettingRow(tr("Reset the tiles")) { onResetTiles() }
        SettingRow(tr("Phone settings")) { start(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        SettingRow(tr("Choose another home screen")) { start(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
    }
}
