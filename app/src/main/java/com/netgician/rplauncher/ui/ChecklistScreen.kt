package com.netgician.rplauncher.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.netgician.rplauncher.calls.PhoneRole
import com.netgician.rplauncher.system.AlertsStore
import com.netgician.rplauncher.texts.TextRole

/** One thing RP Launcher can ask Android for, and what it's used for. */
private class Need(val title: String, val why: String, val permissions: List<String>)

private val NEEDS: List<Need> = buildList {
    add(Need("Contacts", "names in texts and calls", listOf(Manifest.permission.READ_CONTACTS)))
    add(Need("Phone calls", "calling from a tile in one step", listOf(Manifest.permission.CALL_PHONE)))
    add(Need("Camera", "See and Read text", listOf(Manifest.permission.CAMERA)))
    add(Need("Microphone", "speaking texts and voice messages", listOf(Manifest.permission.RECORD_AUDIO)))
    add(
        Need(
            "Location", "SOS and Where am I",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        ),
    )
    if (Build.VERSION.SDK_INT >= 33) {
        add(Need("Notifications", "new texts and missed calls", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

/**
 * Android 13 and later block some settings for apps installed from a downloaded file (like a
 * test build from GitHub): notification access and, from Android 15, the phone and texting app
 * roles. They're unblocked with "Allow restricted settings" on the app's App info page.
 */
internal object RestrictedSettings {
    /** True when this copy was installed from a downloaded file, so the block can apply. */
    fun apply(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        return source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
    }

    /** Best guess whether they've been allowed: a setting they guard is already on, or Android's own switch says so. */
    fun allowed(context: Context): Boolean {
        if (AlertsStore.hasAccess(context)) return true
        val mode = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), context.packageName)
        }.getOrNull()
        return mode == AppOpsManager.MODE_ALLOWED
    }

    const val STEPS = "Android blocks some settings for apps installed from a website, until you allow them. " +
        "1. Tap Alerts access below. If Android says Restricted setting, tap OK. " +
        "2. Tap Allow restricted settings: App info opens. " +
        "3. Tap the three dots at the top right, then Allow restricted settings, and confirm with your PIN or fingerprint. " +
        "4. Come back here and tap Alerts access, Phone app and Texting app again."
}

/**
 * Everything to set up, in one list. Shown after the first viewing window check, and from
 * Setup. Each line says where it stands; a tap opens the place to change it, including
 * Android's own pages for permissions, default apps and restricted settings.
 */
@Composable
fun ChecklistScreen(state: AppState, firstRun: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    val c = state.config
    // Statuses are read again whenever this screen comes back from one of Android's pages.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val homeRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    var asking by remember { mutableStateOf<Need?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val need = asking
        asking = null
        tick++
        if (need == null) return@rememberLauncherForActivityResult
        val granted = result.values.any { it } || need.permissions.any { has(context, it) }
        when {
            granted -> state.say("${need.title} allowed")
            activity != null && need.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> {
                // Android has stopped asking; the switch is on App info now.
                state.say("Android won't ask again. On App info, tap Permissions, then ${need.title}, then Allow.")
                state.start(AlertsStore.appInfoIntent(context), "Could not open app info")
            }
            else -> state.say("${need.title} not allowed. Tap it again to ask again.")
        }
    }
    LaunchedEffect(Unit) {
        state.say(
            if (firstRun) "Welcome to RP Launcher. This list sets up everything, one line at a time. Each line says where it stands. Tap Done when you're finished."
            else "Setup checklist",
        )
    }

    val roles = context.getSystemService(RoleManager::class.java)
    val now = remember(tick) { Statuses.read(context, roles) }

    HelperColumn(state) {
        SettingRow(if (firstRun) "Done, go to the home screen" else "Done") {
            state.go(if (firstRun) Screen.Home else Screen.Settings)
        }
        if (firstRun) Words(state, "Tap each line to set it up. You can come back to this list any time: Setup, then Setup checklist.")

        // ---- Fitting the screen
        SettingRow("Viewing window", if (c.windowChecked) "checked" else "not checked") {
            state.go(Screen.WindowCheck(Screen.Checklist(firstRun)))
        }
        SettingRow("Frame around the window", if (c.windowFrame) "on" else "off") {
            val next = !c.windowFrame
            state.update { it.copy(windowFrame = next) }
            state.say(if (next) "Frame on" else "Frame off")
        }
        SettingRow("Vision mode", c.visionMode.label) { state.go(Screen.Settings) }
        SettingRow("Colours, text size and speech") { state.go(Screen.Settings) }

        // ---- Android's choices
        SettingRow("Home screen", if (now.isHome) "RP Launcher" else "not yet") {
            if (now.isHome) state.say("RP Launcher is already the home screen")
            else if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) homeRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else state.start(Intent(Settings.ACTION_HOME_SETTINGS), "Could not open home screen settings")
        }
        if (now.restricted) {
            SettingRow("Allow restricted settings", if (now.unblocked) "allowed" else "needed") {
                state.say(RestrictedSettings.STEPS)
                state.start(AlertsStore.appInfoIntent(context), "Could not open app info")
            }
            // The steps stay in view until Alerts access, the surest sign, is on.
            if (!now.alerts) Words(state, RestrictedSettings.STEPS)
        }
        SettingRow("Alerts access (notifications read aloud)", if (now.alerts) "on" else "off") {
            state.start(AlertsStore.accessIntent(context), "Could not open the setting")
        }
        SettingRow("Phone app", now.phoneApp ?: "none set") { state.go(Screen.PhoneSwitch) }
        SettingRow("Texting app", now.textApp ?: "none set") { state.go(Screen.TextsSwitch) }
        now.fullScreen?.let { canFullScreen ->
            SettingRow("Call screen over the lock screen", if (canFullScreen) "allowed" else "not allowed") {
                if (canFullScreen || Build.VERSION.SDK_INT < 34) state.say("Allowed")
                else state.start(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.fromParts("package", context.packageName, null)),
                    "Could not open the setting",
                )
            }
        }

        // ---- Permissions, each asked on its own so it's clear what it's for
        NEEDS.forEach { need ->
            val ok = now.granted[need.title] == true
            SettingRow("${need.title} (${need.why})", if (ok) "allowed" else "not allowed") {
                if (ok) state.say("${need.title} is allowed")
                else {
                    asking = need
                    ask.launch(need.permissions.toTypedArray())
                }
            }
        }

        // ---- The person's own things
        SettingRow("Tiles", "${c.tiles.size}") { state.go(Screen.EditTiles) }
        SettingRow("SOS contacts", if (c.sosContacts.isEmpty()) "not set up" else "${c.sosContacts.size}") { state.go(Screen.SosSetup) }
        SettingRow("Helper PIN", if (c.pinHash == null) "not set" else "set") { state.go(Screen.Pin(PinPurpose.Set)) }
        SettingRow("All other settings") { state.go(Screen.Settings) }
        SettingRow(if (firstRun) "Done, go to the home screen" else "Done") {
            state.go(if (firstRun) Screen.Home else Screen.Settings)
        }
    }
}

/** Where each line stands, read once each time the checklist comes back into view. */
private class Statuses(
    val isHome: Boolean,
    val restricted: Boolean,
    val unblocked: Boolean,
    val alerts: Boolean,
    val phoneApp: String?,
    val textApp: String?,
    /** Null before Android 14, where it's always allowed. */
    val fullScreen: Boolean?,
    val granted: Map<String, Boolean>,
) {
    companion object {
        fun read(context: Context, roles: RoleManager?): Statuses {
            val restricted = RestrictedSettings.apply(context)
            return Statuses(
                isHome = runCatching { roles?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false),
                restricted = restricted,
                unblocked = restricted && RestrictedSettings.allowed(context),
                alerts = AlertsStore.hasAccess(context),
                phoneApp = PhoneRole.currentAppName(context),
                textApp = TextRole.currentAppName(context),
                fullScreen = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true }.getOrDefault(true)
                } else null,
                granted = NEEDS.associate { n -> n.title to n.permissions.any { has(context, it) } },
            )
        }
    }
}

private fun has(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
