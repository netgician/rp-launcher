package com.netgician.rplauncher.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Pin
import com.netgician.rplauncher.model.Themes
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.after

/** A scrolling column of big rows, inside the viewing window. Used by the helper screens. */
@Composable
private fun HelperColumn(state: AppState, content: @Composable ColumnScope.() -> Unit) {
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
    LaunchedEffect(Unit) { state.say("Setup") }

    HelperColumn(state) {
        SettingRow("Done") { state.go(Screen.Home) }

        SettingRow("Colours", c.theme.name) {
            val next = Themes.next(c.theme)
            state.update { it.copy(themeId = next.id) }
            state.say(next.name)
        }
        SettingRow("Columns", "${c.cols}") {
            val next = LauncherConfig.COLUMN_CHOICES.after(c.cols)
            state.update { it.copy(cols = next) }
            state.say("$next columns")
        }
        SettingRow("Rows", "${c.rows}") {
            val next = LauncherConfig.ROW_CHOICES.after(c.rows)
            state.update { it.copy(rows = next) }
            state.say("$next rows")
        }
        val size = LauncherConfig.TEXT_SIZES.firstOrNull { it.scale == c.textScale } ?: LauncherConfig.TEXT_SIZES[1]
        SettingRow("Text size", size.name) {
            val next = LauncherConfig.TEXT_SIZES.after(size)
            state.update { it.copy(textScale = next.scale) }
            state.say("${next.name} text")
        }
        SettingRow("Speak on long press", if (c.speakOnLongPress) "On" else "Off") {
            val on = !c.speakOnLongPress
            state.update { it.copy(speakOnLongPress = on) }
            state.say(if (on) "Long press speaks" else "Long press is silent")
        }
        val speed = LauncherConfig.SPEECH_SPEEDS.firstOrNull { it.rate == c.speechRate } ?: LauncherConfig.SPEECH_SPEEDS[1]
        SettingRow("Speech speed", speed.name) {
            val next = LauncherConfig.SPEECH_SPEEDS.after(speed)
            state.update { it.copy(speechRate = next.rate) }
            state.speaker.rate = next.rate
            state.say("${next.name} speech")
        }
        SettingRow("Viewing window check") { state.go(Screen.WindowCheck(Screen.Settings)) }
        SettingRow("Edit tiles") { state.go(Screen.EditTiles) }
        SettingRow(if (c.pinHash == null) "Set a helper PIN" else "Change the helper PIN") {
            state.go(Screen.Pin(PinPurpose.Set))
        }
        if (c.pinHash != null) {
            SettingRow("Remove the helper PIN") {
                state.update { it.copy(pinHash = null) }
                state.say("PIN removed")
            }
        }
        SettingRow("Make RP Launcher the home screen") {
            requestHomeRole(context, state) { homeRole.launch(it) }
        }
        SettingRow("Save setup to a file") { exportFile.launch("rp-launcher-setup.json") }
        SettingRow("Load setup from a file") {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        SettingRow("Version", version ?: "unknown") { state.say("RP Launcher version ${version ?: "unknown"}") }
    }
}

private fun requestHomeRole(context: Context, state: AppState, launch: (Intent) -> Unit) {
    val roles = context.getSystemService(RoleManager::class.java)
    if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            state.say("RP Launcher is already the home screen")
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
        mutableStateOf(if (purpose == PinPurpose.Unlock) "Helper PIN" else "Choose a PIN")
    }
    LaunchedEffect(purpose) {
        state.say(
            if (purpose == PinPurpose.Unlock) "Enter the helper PIN"
            else "Choose a PIN of at least ${Pin.MIN_LENGTH} digits",
        )
    }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "ok")
    InWindow(state.config.window) {
        PagedTiles(
            items = keys,
            page = 0,
            onPage = { _, _ -> },
            cols = 3,
            rows = 4,
            onSpeak = state::say,
            header = { m ->
                val shown = if (entered.isEmpty()) message else "• ".repeat(entered.length).trim()
                SpokenText(shown, m, state::say, spoken = "$message. ${entered.length} digits entered")
            },
            minTarget = 0f,
        ) { key, m ->
            when (key) {
                "clear" -> if (entered.isEmpty()) {
                    Tile("Cancel", m) { state.back() }
                } else {
                    Tile("Clear", m, spoken = "Clear the digits") { entered = "" }
                }
                "ok" -> Tile("OK", m, emphasized = true) {
                    when (purpose) {
                        PinPurpose.Unlock -> if (Pin.matches(entered, state.config.pinHash)) {
                            state.go(Screen.Settings)
                        } else {
                            entered = ""
                            message = "Wrong PIN. Try again"
                            state.say(message)
                        }
                        PinPurpose.Set -> if (entered.length >= Pin.MIN_LENGTH) {
                            val hash = Pin.hash(entered)
                            state.update { it.copy(pinHash = hash) }
                            state.say("PIN saved")
                            state.go(Screen.Settings)
                        } else {
                            message = "Use at least ${Pin.MIN_LENGTH} digits"
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
    LaunchedEffect(Unit) { state.say("Edit tiles") }
    HelperColumn(state) {
        SettingRow("Back to setup") { state.go(Screen.Settings) }
        SettingRow("Add a tile") { state.go(Screen.AddTile) }
        tiles.forEachIndexed { index, spec ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(2.dp, lv.accent), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().semantics { contentDescription = "Tile ${index + 1}, ${spec.label}" }) {
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
                    Tile("Up", Modifier.weight(1f).fillMaxHeight(), spoken = "Move ${spec.label} up") {
                        move(state, index, -1)
                    }
                    Tile("Down", Modifier.weight(1f).fillMaxHeight(), spoken = "Move ${spec.label} down") {
                        move(state, index, 1)
                    }
                    Tile("Remove", Modifier.weight(1f).fillMaxHeight(), spoken = "Remove ${spec.label}") {
                        state.update { c -> c.copy(tiles = c.tiles.filterIndexed { i, _ -> i != index }) }
                        state.say("${spec.label} removed")
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
        state.say(if (delta < 0) "Already first" else "Already last")
        return
    }
    val moved = tiles.toMutableList().apply { add(target, removeAt(index)) }
    state.update { it.copy(tiles = moved) }
    state.say("${tiles[index].label} is now number ${target + 1}")
}

@Composable
fun AddTileScreen(state: AppState) {
    val context = LocalContext.current
    fun add(spec: TileSpec) {
        state.update { it.copy(tiles = it.tiles + spec) }
        state.say("${spec.label} added")
        state.go(Screen.EditTiles)
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val contact = readPhoneContact(context, uri)
        if (contact == null) state.say("Could not read that contact") else add(contact)
    }
    LaunchedEffect(Unit) { state.say("Add a tile") }
    HelperColumn(state) {
        SettingRow("Back") { state.go(Screen.EditTiles) }
        SettingRow("An app") { state.go(Screen.PickApp) }
        SettingRow("A person to call") {
            runCatching {
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }.onFailure { state.say("No contacts app found") }
        }
        SettingRow("Phone") { add(TileSpec.Phone) }
        SettingRow("Messages") { add(TileSpec.Messages) }
        SettingRow("Contacts") { add(TileSpec.Contacts) }
        SettingRow("Torch") { add(TileSpec.Torch) }
        SettingRow("All apps") { add(TileSpec.AllApps) }
    }
}

/** Reads the picked phone number. The picker grants access to that one row, so no contacts permission. */
private fun readPhoneContact(context: Context, uri: Uri): TileSpec.Contact? = runCatching {
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
        val notice = "RP Launcher had trouble starting. These buttons still work."
        Box(Modifier.fillMaxWidth().semantics { contentDescription = notice }) {
            FitText(notice, lv.fg, maxSp = 26f, maxLines = 3, modifier = Modifier.fillMaxWidth())
        }
        SettingRow("Phone") { start(Intent(Intent.ACTION_DIAL)) }
        SettingRow("Contacts") {
            start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS))
        }
        SettingRow("Try again") { onTryAgain() }
        SettingRow("Reset the tiles") { onResetTiles() }
        SettingRow("Phone settings") { start(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        SettingRow("Choose another home screen") { start(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
    }
}
