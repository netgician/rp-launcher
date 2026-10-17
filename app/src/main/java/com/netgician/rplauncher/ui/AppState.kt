package com.netgician.rplauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.Apps
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Torch

sealed interface Screen {
    data object Home : Screen
    data class WindowCheck(val returnTo: Screen) : Screen
    data object Apps : Screen
    data class ConfirmCall(val contact: TileSpec.Contact) : Screen
    data class Pin(val purpose: PinPurpose) : Screen
    data object Settings : Screen
    data object EditTiles : Screen
    data object AddTile : Screen
    data object PickApp : Screen
}

enum class PinPurpose { Unlock, Set }

/** Everything the screens share: the saved setup, where we are, speech and the torch. */
class AppState(
    private val activity: Activity,
    private val store: ConfigStore,
    val speaker: Speaker,
    val torch: Torch,
) {
    var config by mutableStateOf(store.load())
        private set

    var screen by mutableStateOf<Screen>(
        if (config.windowChecked) Screen.Home else Screen.WindowCheck(Screen.Home),
    )
        private set

    var homePage by mutableIntStateOf(0)
    var listPage by mutableIntStateOf(0)

    fun update(change: (LauncherConfig) -> LauncherConfig) {
        config = change(config)
        store.save(config)
    }

    fun go(to: Screen) {
        listPage = 0
        screen = to
    }

    fun say(text: String) = speaker.speak(text)

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
            Screen.Apps, is Screen.ConfirmCall, Screen.Settings -> go(Screen.Home)
            Screen.EditTiles -> go(Screen.Settings)
            Screen.AddTile -> go(Screen.EditTiles)
            Screen.PickApp -> go(Screen.AddTile)
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
            TileSpec.Messages -> start(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            )
            TileSpec.Contacts -> start(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
            )
            TileSpec.Torch -> when (torch.toggle()) {
                true -> say("Torch on")
                false -> say("Torch off")
                null -> say("This phone has no torch")
            }
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
