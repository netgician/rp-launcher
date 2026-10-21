package com.netgician.rplauncher

import com.netgician.rplauncher.i18n.tr
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color as ComposeColor
import com.netgician.rplauncher.model.Hints
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Themes
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.Apps
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.CrashGuard
import com.netgician.rplauncher.system.LightSensor
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Torch
import com.netgician.rplauncher.system.Vibes
import com.netgician.rplauncher.texts.ComposeActivity
import com.netgician.rplauncher.texts.MmsDownloads
import com.netgician.rplauncher.texts.TextNotifications
import com.netgician.rplauncher.texts.TextRole
import com.netgician.rplauncher.texts.TextsBus
import com.netgician.rplauncher.ui.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private lateinit var torch: Torch
    private lateinit var guard: CrashGuard
    private var lightSensor: LightSensor? = null
    private var state: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        guard = CrashGuard(this)
        speaker = Speaker(this)
        torch = Torch(this)

        if (guard.shouldShowSafeScreen()) {
            showSafeScreen()
            return
        }
        guard.markStarting()

        val s = AppState(this, ConfigStore(this), speaker, torch, Vibes(this), lifecycleScope)
        // The phone's language may have changed since RP Launcher started.
        com.netgician.rplauncher.i18n.Lang.choose(s.config.language)
        state = s
        handleTextIntent(intent)
        lightSensor = LightSensor(this) { lux -> s.onLux(lux) }
        setContent {
            val brightness = s.windowBrightness
            LaunchedEffect(brightness) { applyBrightness(brightness) }
            LaunchedEffect(Unit) {
                if (s.screen == Screen.Home) s.announceMode()
                s.remindWindowCheck()
                delay(3_000)
                guard.markStarted()
            }
            // Texts: say what arrived, what was sent, and what didn't go through.
            LaunchedEffect(Unit) {
                TextsBus.events.collect { s.texts.onEvent(it) }
            }
            // Automatic night colours by clock: check now, then every minute.
            LaunchedEffect(s.config.autoNight, s.config.nightFromHour, s.config.nightUntilHour) {
                while (true) {
                    s.refreshNightByClock()
                    delay(60_000L - (System.currentTimeMillis() % 60_000L))
                }
            }
            Root(s)
        }
    }

    private fun showSafeScreen() {
        setContent {
            CompositionLocalProvider(LocalLv provides LvStyle(Themes.Dark, AtkinsonFamily, 1f)) {
                SafeScreen(
                    start = { intent -> runCatching { startActivity(intent) } },
                    onTryAgain = {
                        guard.reset()
                        recreate()
                    },
                    onResetTiles = {
                        ConfigStore(this).save(LauncherConfig())
                        guard.reset()
                        recreate()
                    },
                )
            }
        }
    }

    /** Night themes dim the launcher's own window; otherwise the limit from Setup, or the phone's setting. */
    private fun applyBrightness(level: Float?) {
        val attrs = window.attributes
        attrs.screenBrightness = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) state?.onHomePressed()
        handleTextIntent(intent)
    }

    /** Opened from a text notification, or from another app's "send a text". */
    private fun handleTextIntent(intent: Intent?) {
        val s = state ?: return
        when (intent?.action) {
            StoreShots.ACTION -> if (s.isTestBuild) StoreShots.show(s, this, intent.getStringExtra(StoreShots.EXTRA_SCREEN).orEmpty(), intent.getStringExtra(StoreShots.EXTRA_LANGUAGE))
            TextNotifications.ACTION_OPEN_THREAD -> {
                val thread = intent.getLongExtra(TextNotifications.EXTRA_THREAD, -1L)
                if (thread >= 0 && s.config.windowChecked) s.texts.openThread(thread)
            }
            com.netgician.rplauncher.calls.DialActivity.ACTION_DIAL_NUMBER -> {
                if (s.config.windowChecked) s.go(Screen.Dial(intent.getStringExtra(com.netgician.rplauncher.calls.DialActivity.EXTRA_NUMBER).orEmpty()))
            }
            ComposeActivity.ACTION_COMPOSE -> {
                if (!s.config.windowChecked) return
                val to = intent.getStringArrayListExtra(ComposeActivity.EXTRA_TO).orEmpty()
                val files = intent.getStringArrayListExtra(ComposeActivity.EXTRA_FILES).orEmpty()
                val types = intent.getStringArrayListExtra(ComposeActivity.EXTRA_TYPES).orEmpty()
                s.texts.compose(
                    to,
                    intent.getStringExtra(ComposeActivity.EXTRA_BODY),
                    files.mapIndexed { i, f -> DraftFile(f, types.getOrNull(i) ?: "application/octet-stream") },
                )
            }
        }
        // Handled once; a later restart shouldn't open it again.
        if (intent != null && (intent.action == TextNotifications.ACTION_OPEN_THREAD || intent.action == ComposeActivity.ACTION_COMPOSE ||
                intent.action == com.netgician.rplauncher.calls.DialActivity.ACTION_DIAL_NUMBER)
        ) {
            intent.action = null
        }
    }

    override fun onStart() {
        super.onStart()
        TextsBus.foreground = true
        com.netgician.rplauncher.system.Timer.appVisible = true
        torch.start()
        lightSensor?.start()
        state?.refreshNightByClock()
        // A tile added from another app ("Add to home screen" in Chrome) while this screen was away.
        state?.reloadFromStore()
        if (com.netgician.rplauncher.ui.Demo.expire()) state?.texts?.demoChanged()
        state?.texts?.refreshUnread()
        if (TextRole.isDefault(this)) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { MmsDownloads.resume(this@MainActivity) } }
        }
    }

    override fun onStop() {
        super.onStop()
        TextsBus.foreground = false
        com.netgician.rplauncher.system.Timer.appVisible = false
        torch.stop()
        lightSensor?.stop()
        speaker.stop()
        state?.texts?.stopPlaying()
    }

    override fun onDestroy() {
        speaker.shutdown()
        state?.closeOcr()
        super.onDestroy()
    }
}

@Composable
fun Root(state: AppState) {
    val c = state.config
    val theme = state.theme
    val style = remember(theme, c.textScale) { LvStyle(theme, AtkinsonFamily, c.textScale) }
    SideEffect {
        NightNow.active = state.nightActive
        state.speaker.rate = c.speechRate
        state.speaker.pitch = c.speechPitch
        state.speaker.voiceName = c.voiceName
    }
    val tapMode = TapMode(
        touchToHear = touchToHearOn(c, state.speaker.screenReaderOn, state.screen),
        heard = state.heard,
        onHear = { label ->
            state.heard = label
            state.say(Hints.heard(label, c.spokenHints))
        },
        speak = state::say,
    )
    // The dark screen draws black over everything; touches and speech still work underneath.
    val curtain = if (state.darkScreenNow) Modifier.drawWithContent {
        drawContent()
        drawRect(ComposeColor.Black)
    } else Modifier
    CompositionLocalProvider(LocalLv provides style, LocalTapMode provides tapMode, LocalWindowFrame provides c.windowFrame) {
        BackHandler { state.back() }
        // The timer rings on time while RP Launcher is open, even if Android holds its alarm back.
        val timerContext = androidx.compose.ui.platform.LocalContext.current
        val timer by com.netgician.rplauncher.system.Timer.state.collectAsState()
        androidx.compose.runtime.LaunchedEffect(timer.phase, timer.endsAt) {
            if (timer.phase == com.netgician.rplauncher.model.TimerState.Phase.RUNNING && com.netgician.rplauncher.system.Timer.inAppCheck) {
                kotlinx.coroutines.delay((timer.endsAt - System.currentTimeMillis()).coerceAtLeast(0L))
                com.netgician.rplauncher.system.Timer.tick(timerContext)
            }
        }
        // A short fade between screens: no sudden jumps, and never a white frame.
        // Test builds only: see each screen through a tunnel, as someone with RP might.
        val tunnel = state.tunnel
        val tunnelView = if (tunnel > 0f) Modifier.drawWithContent {
            drawContent()
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * c.window.centerYFraction)
            val hole = androidx.compose.ui.graphics.Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(center, size.width * tunnel))
            }
            clipPath(hole, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(ComposeColor.Black) }
            // Lower contrast inside the tunnel too, as RP often does.
            drawCircle(ComposeColor(0x40808080), radius = size.width * tunnel, center = center)
        } else Modifier
        Box(Modifier.fillMaxSize().then(curtain).then(tunnelView)) {
            Crossfade(targetState = state.screen, animationSpec = tween(180), label = "screen") { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(state)
                    is Screen.WindowCheck -> WindowCheckScreen(state, screen.returnTo)
                    Screen.Apps -> AppListScreen(
                        state = state,
                        title = tr("All apps"),
                        leaveLabel = tr("Home"),
                        onLeave = { state.go(Screen.Home) },
                    ) { app ->
                        state.start(Apps.launchIntent(app.packageName, app.className))
                        state.go(Screen.Home)
                    }
                    Screen.See -> SeeScreen(state)
                    Screen.ReadText -> TextReaderScreen(state)
                    Screen.Alerts -> AlertsScreen(state)
                    is Screen.ConfirmCall -> ConfirmCallScreen(state, screen.contact)
                    is Screen.Pin -> PinScreen(state, screen.purpose)
                    Screen.PinReset -> PinResetScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                    is Screen.Checklist -> ChecklistScreen(state, screen.firstRun)
                    Screen.EditTiles -> EditTilesScreen(state)
                    Screen.AddTile -> AddTileScreen(state)
                    Screen.Texts -> TextsScreen(state)
                    is Screen.Conversation -> ConversationScreen(state, screen.threadId)
                    is Screen.Message -> MessageScreen(state, screen.threadId, screen.key)
                    Screen.Compose -> ComposeScreen(state)
                    Screen.ComposeMore -> ComposeMoreScreen(state)
                    is Screen.Attachments -> AttachmentsScreen(state, screen.threadId)
                    Screen.QuickReply -> QuickReplyScreen(state)
                    Screen.NewText -> NewTextScreen(state)
                    is Screen.NumberPad -> NumberPadScreen(state, screen.purpose)
                    Screen.AddPhoto -> AddPhotoScreen(state)
                    Screen.Record -> RecordScreen(state)
                    is Screen.Picture -> PictureScreen(state, screen)
                    is Screen.Card -> CardScreen(state, screen)
                    is Screen.Invite -> InviteScreen(state, screen)
                    Screen.TextsSwitch -> TextsSwitchScreen(state)
                    Screen.TextsCheck -> TextsCheckScreen(state)
                    Screen.TextsNotSet -> TextsNotSetScreen(state)
                    is Screen.Sos -> SosScreen(state, screen.practice)
                    Screen.PhoneHome -> PhoneHomeScreen(state)
                    is Screen.Dial -> DialScreen(state, screen.number)
                    Screen.Recents -> RecentsScreen(state)
                    Screen.PhoneSwitch -> PhoneSwitchScreen(state)
                    Screen.Voicemail -> VoicemailScreen(state)
                    is Screen.VoicemailMessage -> VoicemailMessageScreen(state, screen.id)
                    Screen.VoicemailSetup -> VoicemailSetupScreen(state)
                    Screen.WhereAmI -> WhereAmIScreen(state)
                    Screen.SosSetup -> SosSetupScreen(state)
                    Screen.Pages -> PagesScreen(state)
                    Screen.Timer -> TimerScreen(state)
                    is Screen.AlarmList -> AlarmListScreen(state, screen.reminders)
                    is Screen.AlarmEdit -> AlarmEditScreen(state, screen.id, screen.reminder)
                    Screen.AlarmMessage -> AlarmMessageScreen(state)
                    Screen.AlarmTime -> AlarmTimeScreen(state)
                    Screen.AlarmAmPm -> AlarmAmPmScreen(state)
                    Screen.AlarmDays -> AlarmDaysScreen(state)
                    Screen.AlarmPickDays -> AlarmPickDaysScreen(state)
                    Screen.Notes -> NotesScreen(state)
                    Screen.NoteRecord -> NoteRecordScreen(state)
                    is Screen.Note -> NoteScreen(state, screen.name)
                    Screen.Calculator -> CalculatorScreen(state)
                    Screen.Music -> MediaScreen(state)
                    Screen.LightFinder -> LightFinderScreen(state)
                    Screen.PickShortcutApp -> AppListScreen(
                        state = state,
                        title = tr("Shortcut from which app?"),
                        leaveLabel = tr("Back"),
                        onLeave = { state.go(Screen.AddTile) },
                    ) { app -> state.go(Screen.PickShortcut(app.packageName, app.label)) }
                    is Screen.PickShortcut -> PickShortcutScreen(state, screen.packageName, screen.appLabel)
                    Screen.PickApp -> AppListScreen(
                        state = state,
                        title = tr("Pick an app"),
                        leaveLabel = tr("Back"),
                        onLeave = { state.go(Screen.AddTile) },
                    ) { app ->
                        state.update { it.copy(tiles = it.tiles + TileSpec.App(app.packageName, app.className, app.label)) }
                        state.say(tr("%s added", app.label))
                        state.go(Screen.EditTiles)
                    }
                }
            }
        }
    }
}
