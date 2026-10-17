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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Themes
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.Apps
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.CrashGuard
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Torch
import com.netgician.rplauncher.ui.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private lateinit var torch: Torch
    private lateinit var guard: CrashGuard
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

        val s = AppState(this, ConfigStore(this), speaker, torch)
        state = s
        setContent {
            val brightness = s.config.theme.windowBrightness
            LaunchedEffect(brightness) { applyBrightness(brightness) }
            LaunchedEffect(Unit) {
                delay(3_000)
                guard.markStarted()
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

    /** Night themes dim the launcher's own window; other themes leave the phone's brightness alone. */
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
    }

    override fun onStop() {
        super.onStop()
        torch.stop()
        speaker.stop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}

@Composable
fun Root(state: AppState) {
    val c = state.config
    val style = remember(c.themeId, c.textScale) { LvStyle(c.theme, AtkinsonFamily, c.textScale) }
    SideEffect { state.speaker.rate = c.speechRate }
    CompositionLocalProvider(LocalLv provides style) {
        BackHandler { state.back() }
        when (val s = state.screen) {
            Screen.Home -> HomeScreen(state)
            is Screen.WindowCheck -> WindowCheckScreen(state, s.returnTo)
            Screen.Apps -> AppListScreen(
                state = state,
                title = "All apps",
                leaveLabel = "Home",
                onLeave = { state.go(Screen.Home) },
            ) { app ->
                state.start(Apps.launchIntent(app.packageName, app.className))
                state.go(Screen.Home)
            }
            is Screen.ConfirmCall -> ConfirmCallScreen(state, s.contact)
            is Screen.Pin -> PinScreen(state, s.purpose)
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
