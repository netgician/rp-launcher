package com.netgician.rplauncher

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import com.netgician.rplauncher.ui.*
import kotlinx.coroutines.delay

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

        val s = AppState(this, ConfigStore(this), speaker, torch, Vibes(this))
        state = s
        lightSensor = LightSensor(this) { lux -> s.onLux(lux) }
        setContent {
            val brightness = s.windowBrightness
            LaunchedEffect(brightness) { applyBrightness(brightness) }
            LaunchedEffect(Unit) {
                if (s.screen == Screen.Home) s.announceMode()
                delay(3_000)
                guard.markStarted()
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
    }

    override fun onStart() {
        super.onStart()
        torch.start()
        lightSensor?.start()
        state?.refreshNightByClock()
    }

    override fun onStop() {
        super.onStop()
        torch.stop()
        lightSensor?.stop()
        speaker.stop()
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
    SideEffect { state.speaker.rate = c.speechRate }
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
    CompositionLocalProvider(LocalLv provides style, LocalTapMode provides tapMode) {
        BackHandler { state.back() }
        // A short fade between screens: no sudden jumps, and never a white frame.
        Box(Modifier.fillMaxSize().then(curtain)) {
            Crossfade(targetState = state.screen, animationSpec = tween(180), label = "screen") { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(state)
                    is Screen.WindowCheck -> WindowCheckScreen(state, screen.returnTo)
                    Screen.Apps -> AppListScreen(
                        state = state,
                        title = "All apps",
                        leaveLabel = "Home",
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
                    Screen.EditTiles -> EditTilesScreen(state)
                    Screen.AddTile -> AddTileScreen(state)
                    Screen.PickApp -> AppListScreen(
                        state = state,
                        title = "Pick an app",
                        leaveLabel = "Back",
                        onLeave = { state.go(Screen.AddTile) },
                    ) { app ->
                        state.update { it.copy(tiles = it.tiles + TileSpec.App(app.packageName, app.className, app.label)) }
                        state.say("${app.label} added")
                        state.go(Screen.EditTiles)
                    }
                }
            }
        }
    }
}
