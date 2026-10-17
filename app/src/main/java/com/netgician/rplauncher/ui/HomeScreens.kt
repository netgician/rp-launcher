package com.netgician.rplauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.R
import com.netgician.rplauncher.model.Landmarks
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.AppEntry
import com.netgician.rplauncher.system.Apps
import com.netgician.rplauncher.system.AlertsStore
import com.netgician.rplauncher.system.Battery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private sealed interface HomeItem {
    data class Spec(val spec: TileSpec) : HomeItem
    data object Setup : HomeItem
}

/**
 * The long-press action: speak the tile, and with touch landmarks on, buzz once per row
 * so the person can feel where their finger is. Null when both are off.
 */
fun longPress(state: AppState, text: String, pos: TilePos? = null): (() -> Unit)? {
    val speak = state.config.speakOnLongPress
    val feel = state.config.touchLandmarks && pos != null
    if (!speak && !feel) return null
    return {
        if (feel && pos != null) state.vibes.play(Landmarks.rowPattern(pos.row))
        if (speak) state.say(text)
    }
}

fun iconFor(spec: TileSpec): TileIcon? = when (spec) {
    TileSpec.Phone -> TileIcon.Vector(Icons.Filled.Call)
    TileSpec.Messages -> TileIcon.Vector(Icons.Filled.Email)
    TileSpec.Contacts -> TileIcon.Vector(Icons.Filled.Person)
    TileSpec.Torch -> TileIcon.Drawable(R.drawable.ic_torch)
    TileSpec.See -> TileIcon.Vector(Icons.Filled.Search)
    TileSpec.Alerts -> TileIcon.Vector(Icons.Filled.Notifications)
    TileSpec.AllApps -> TileIcon.Vector(Icons.Filled.Menu)
    is TileSpec.App -> null
    is TileSpec.Contact -> TileIcon.Vector(Icons.Filled.Call)
}

@Composable
fun HomeScreen(state: AppState) {
    val c = state.config
    // The Setup tile is always last, so a helper can always get in.
    val items = remember(c.tiles) { c.tiles.map<TileSpec, HomeItem> { HomeItem.Spec(it) } + HomeItem.Setup }
    val alerts by AlertsStore.items.collectAsState()
    InWindow(c.window) {
        PagedTiles(
            items = items,
            page = state.homePage,
            onPage = { p, n ->
                state.homePage = p
                state.onPageTurned(p, n)
            },
            cols = c.homeCols,
            rows = c.rows,
            onSpeak = state::say,
            header = { m -> StatusHeader(m, state::say) },
        ) { item, m, pos ->
            when (item) {
                HomeItem.Setup -> Tile(
                    label = "Setup",
                    modifier = m,
                    spoken = "Setup, for helpers",
                    icon = TileIcon.Vector(Icons.Filled.Settings),
                    onLongPress = longPress(state, "Setup, for helpers", pos),
                ) { state.openSetup() }
                is HomeItem.Spec -> SpecTile(state, item.spec, m, pos, alerts.size)
            }
        }
    }
}

@Composable
private fun SpecTile(state: AppState, spec: TileSpec, m: Modifier, pos: TilePos, alertCount: Int) {
    val torchOn = spec == TileSpec.Torch && state.torch.isOn
    val label = when {
        torchOn -> "Torch on"
        spec == TileSpec.Alerts && alertCount > 0 -> "Alerts ($alertCount)"
        else -> spec.label
    }
    val spoken = when {
        spec is TileSpec.Contact -> "Call ${spec.name}"
        torchOn -> "Torch is on"
        spec == TileSpec.Alerts -> if (alertCount == 0) "Alerts, none new" else "Alerts, $alertCount new"
        else -> spec.label
    }
    Tile(
        label = label,
        modifier = m,
        spoken = spoken,
        icon = iconFor(spec),
        emphasized = torchOn,
        onLongPress = longPress(state, spoken, pos),
    ) { state.open(spec) }
}

/** Time and battery inside the window. Tap to hear them. */
@Composable
fun StatusHeader(modifier: Modifier, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            now = System.currentTimeMillis()
        }
    }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val battery = remember(now) { Battery.read(context) }
    val shown = if (battery.percent >= 0) "$time · ${battery.percent}%" else time
    val spoken = buildString {
        append("It's $time.")
        if (battery.percent >= 0) {
            append(" Battery ${battery.percent} percent")
            if (battery.charging) append(", charging")
            append(".")
        }
    }
    SpokenText(shown, modifier, onSpeak, spoken = spoken, maxSp = 30f)
}

private sealed interface AppListItem {
    data object Leave : AppListItem
    data class App(val app: AppEntry) : AppListItem
}

/** A paged list of apps, one big row each. Used for "All apps" and for picking a tile. */
@Composable
fun AppListScreen(
    state: AppState,
    title: String,
    leaveLabel: String,
    onLeave: () -> Unit,
    onPick: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>()) {
        value = withContext(Dispatchers.Default) { Apps.load(context) }
    }
    val items = remember(apps) { listOf<AppListItem>(AppListItem.Leave) + apps.map { AppListItem.App(it) } }
    LaunchedEffect(title) { state.say(title) }
    InWindow(state.config.window) {
        PagedTiles(
            items = items,
            page = state.listPage,
            onPage = { p, n ->
                state.listPage = p
                state.onPageTurned(p, n)
            },
            cols = 1,
            rows = state.config.rows + 1,
            onSpeak = state::say,
            header = { m -> SpokenText(title, m, state::say) },
        ) { item, m, pos ->
            when (item) {
                AppListItem.Leave -> Tile(
                    label = leaveLabel,
                    modifier = m,
                    icon = TileIcon.Vector(Icons.Filled.Home),
                    onLongPress = longPress(state, leaveLabel, pos),
                ) { onLeave() }
                is AppListItem.App -> Tile(
                    label = item.app.label,
                    modifier = m,
                    onLongPress = longPress(state, item.app.label, pos),
                ) { onPick(item.app) }
            }
        }
    }
}

/** A full-window check before any call: the name is spoken, and nothing dials by accident. */
@Composable
fun ConfirmCallScreen(state: AppState, contact: TileSpec.Contact) {
    val context = LocalContext.current
    val tel = Uri.fromParts("tel", contact.number, null)
    val dial = {
        state.start(Intent(Intent.ACTION_DIAL, tel))
        state.go(Screen.Home)
    }
    val call = {
        state.start(Intent(Intent.ACTION_CALL, tel))
        state.go(Screen.Home)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) call() else dial()
    }
    LaunchedEffect(contact) {
        state.say("Call ${contact.name}? Tap Call to call, Message to send a text, or Cancel.")
    }
    InWindow(state.config.window) {
        PagedTiles(
            items = listOf("call", "message", "cancel"),
            page = 0,
            onPage = { _, _ -> },
            cols = 1,
            rows = 3,
            onSpeak = state::say,
            header = { m -> SpokenText(contact.name, m, state::say, spoken = "Call ${contact.name}?", maxLines = 2) },
            minTarget = 0f,
        ) { item, m, _ ->
            when (item) {
                "call" -> Tile(
                    label = "Call",
                    modifier = m,
                    spoken = "Call ${contact.name}",
                    icon = TileIcon.Vector(Icons.Filled.Call),
                    emphasized = true,
                ) {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) call() else permission.launch(Manifest.permission.CALL_PHONE)
                }
                "message" -> Tile(
                    label = "Message",
                    modifier = m,
                    spoken = "Send ${contact.name} a text",
                    icon = TileIcon.Vector(Icons.Filled.Email),
                ) {
                    state.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact.number))))
                    state.go(Screen.Home)
                }
                else -> Tile(
                    label = "Cancel",
                    modifier = m,
                    icon = TileIcon.Vector(Icons.Filled.Close),
                ) {
                    state.say("Cancelled")
                    state.go(Screen.Home)
                }
            }
        }
    }
}

/**
 * The viewing window check. The person looks at the middle and resizes a bright frame
 * until all four corners are just visible. Everything after this fits inside it.
 */
@Composable
fun WindowCheckScreen(state: AppState, returnTo: Screen) {
    val lv = LocalLv.current
    val w = state.config.window
    val instructions = "Viewing window check. Keep your eyes on the middle of the screen. " +
        "Tap Bigger until you can no longer see all four corners of the frame, then tap Smaller once. " +
        "Tap Done when you can see all four corners."
    LaunchedEffect(Unit) { state.say(instructions) }

    fun change(next: com.netgician.rplauncher.model.ViewingWindow, done: String, limit: String) {
        if (next == w) {
            state.say(limit)
        } else {
            state.update { it.copy(window = next) }
            state.say(done)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val r = w.rectIn(maxWidth.value, maxHeight.value)
        Box(Modifier.place(r).border(BorderStroke(8.dp, lv.accent)).padding(16.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpokenText(
                    text = "Can you see all four corners of the frame?",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSpeak = state::say,
                    spoken = instructions,
                    maxSp = 24f,
                    maxLines = 4,
                )
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Bigger", Modifier.weight(1f).fillMaxHeight(), spoken = "Make the frame bigger") {
                        change(w.bigger(), "Bigger", "This is the largest")
                    }
                    Tile("Smaller", Modifier.weight(1f).fillMaxHeight(), spoken = "Make the frame smaller") {
                        change(w.smaller(), "Smaller", "This is the smallest")
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Up", Modifier.weight(1f).fillMaxHeight(), spoken = "Move the frame up") {
                        change(w.moveUp(), "Up", "This is the top")
                    }
                    Tile("Down", Modifier.weight(1f).fillMaxHeight(), spoken = "Move the frame down") {
                        change(w.moveDown(), "Down", "This is the bottom")
                    }
                }
                Tile(
                    label = "Done",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    spoken = "Done, I can see all four corners",
                    emphasized = true,
                ) {
                    state.update { it.copy(windowChecked = true) }
                    state.say("Saved")
                    state.go(returnTo)
                }
            }
        }
    }
}
