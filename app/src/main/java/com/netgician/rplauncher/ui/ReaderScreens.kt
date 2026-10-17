package com.netgician.rplauncher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.system.AlertsStore

/**
 * The reading viewer: one item at a time in large text in the top half of the window,
 * big controls in the bottom half. Tapping the text reads it again.
 */
@Composable
internal fun <T> ReaderLayout(
    state: AppState,
    body: String,
    onBodyTap: () -> Unit,
    header: String,
    headerSpoken: String,
    controls: List<T>,
    bodyWeight: Float = 0.5f,
    bodyContent: (@Composable BoxScope.() -> Unit)? = null,
    tile: @Composable (item: T, modifier: Modifier, pos: TilePos) -> Unit,
) {
    val lv = LocalLv.current
    var page by remember(controls.size) { mutableIntStateOf(0) }
    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(bodyWeight)
                    .padding(8.dp)
                    .border(BorderStroke(2.dp, lv.accent), RoundedCornerShape(12.dp))
                    .semantics(mergeDescendants = true) { contentDescription = body }
                    .clickable { onBodyTap() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bodyContent != null) {
                    bodyContent()
                } else {
                    FitText(
                        text = body,
                        color = lv.fg,
                        maxSp = 30f * lv.textScale,
                        minSp = 14f,
                        maxLines = 8,
                        align = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f - bodyWeight)) {
                PagedTiles(
                    items = controls,
                    page = page,
                    onPage = { p, n ->
                        page = p
                        state.onPageTurned(p, n)
                    },
                    cols = 3,
                    rows = 2,
                    onSpeak = state::say,
                    header = { m -> SpokenText(header, m, state::say, spoken = headerSpoken, maxSp = 22f) },
                    tile = tile,
                )
            }
        }
    }
}

private enum class ReadControl(val label: String) {
    PREVIOUS("Previous"),
    NEXT("Next line"),
    ALL("Read all"),
    CAMERA("Camera"),
    HOME("Home"),
}

/** Text read by the camera, one line per page, in its own voice (English or Spanish). */
@Composable
fun TextReaderScreen(state: AppState) {
    val read = state.readText
    val lines = read?.lines.orEmpty()
    val language = read?.language ?: Language.ENGLISH
    var index by remember(read) { mutableIntStateOf(0) }

    if (lines.isEmpty()) {
        val advice = read?.hints?.joinToString(" ") ?: "Nothing has been read yet."
        ReaderLayout(
            state = state,
            body = advice,
            onBodyTap = { state.say(advice) },
            header = "No text",
            headerSpoken = "No text found",
            controls = listOf(ReadControl.CAMERA, ReadControl.HOME),
        ) { c, m, pos -> ReadTile(state, c, m, pos) { if (c == ReadControl.CAMERA) state.go(read?.backTo ?: Screen.See) else state.go(Screen.Home) } }
        return
    }

    val i = index.coerceIn(0, lines.lastIndex)
    fun speakLine(n: Int) = state.speaker.speak(lines[n], language)
    ReaderLayout(
        state = state,
        body = lines[i],
        onBodyTap = { speakLine(i) },
        header = "Line ${i + 1} of ${lines.size}",
        headerSpoken = "Line ${i + 1} of ${lines.size}",
        controls = ReadControl.entries.toList(),
    ) { c, m, pos ->
        ReadTile(state, c, m, pos) {
            when (c) {
                ReadControl.PREVIOUS -> if (i > 0) {
                    index = i - 1
                    speakLine(i - 1)
                } else state.say("This is the first line")
                ReadControl.NEXT -> if (i < lines.lastIndex) {
                    index = i + 1
                    speakLine(i + 1)
                } else state.say("That was the last line")
                ReadControl.ALL -> state.speaker.speak(lines.drop(i).joinToString(". "), language)
                ReadControl.CAMERA -> state.go(read?.backTo ?: Screen.See)
                ReadControl.HOME -> state.go(Screen.Home)
            }
        }
    }
}

@Composable
private fun ReadTile(state: AppState, c: ReadControl, m: Modifier, pos: TilePos, onClick: () -> Unit) {
    val icon = when (c) {
        ReadControl.HOME -> TileIcon.Vector(Icons.Filled.Home)
        ReadControl.ALL -> TileIcon.Vector(Icons.Filled.PlayArrow)
        else -> null
    }
    // "Camera" goes back to See; from a picture text it's "Back".
    val label = if (c == ReadControl.CAMERA && (state.readText?.backTo ?: Screen.See) != Screen.See) "Back" else c.label
    Tile(label, m, icon = icon, onLongPress = longPress(state, label, pos), onClick = onClick)
}

private enum class AlertControl(val label: String) {
    PREVIOUS("Previous"),
    NEXT("Next alert"),
    READ("Read again"),
    OPEN("Open"),
    DISMISS("Dismiss"),
    HOME("Home"),
    ALLOW("Allow access"),
    APP_INFO("App info"),
}

private const val ACCESS_HELP = "To read alerts aloud, RP Launcher needs to see notifications. " +
    "Tap Allow access and turn it on. If Android says the setting is restricted, tap App info, " +
    "open the three-dot menu, choose Allow restricted settings, then try again."

/** New notifications, one per page, read aloud in their own language. Nothing is stored or sent. */
@Composable
fun AlertsScreen(state: AppState) {
    val context = LocalContext.current
    val items by AlertsStore.items.collectAsState()
    var access by remember { mutableStateOf(AlertsStore.hasAccess(context)) }
    LifecycleResumeEffect(Unit) {
        access = AlertsStore.hasAccess(context)
        onPauseOrDispose { }
    }
    var index by remember { mutableIntStateOf(0) }
    val i = index.coerceIn(0, maxOf(0, items.lastIndex))
    val current = items.getOrNull(i)

    LaunchedEffect(access, items.isEmpty()) {
        when {
            !access -> state.say(ACCESS_HELP)
            items.isEmpty() -> state.say("No new alerts")
        }
    }
    LaunchedEffect(current?.key) {
        current?.let { state.speaker.speak("Alert ${i + 1} of ${items.size}. ${it.speech}", Language.guess(it.speech)) }
    }

    val tile: @Composable (AlertControl, Modifier, TilePos) -> Unit = { c, m, pos ->
        val icon = when (c) {
            AlertControl.HOME -> TileIcon.Vector(Icons.Filled.Home)
            AlertControl.READ -> TileIcon.Vector(Icons.Filled.PlayArrow)
            AlertControl.DISMISS -> TileIcon.Vector(Icons.Filled.Delete)
            else -> null
        }
        Tile(c.label, m, icon = icon, onLongPress = longPress(state, c.label, pos)) {
            when (c) {
                AlertControl.PREVIOUS -> {
                    if (i > 0) index = i - 1 else state.say("This is the newest alert")
                }
                AlertControl.NEXT -> {
                    if (i < items.lastIndex) index = i + 1 else state.say("That was the last alert")
                }
                AlertControl.READ -> current?.let { state.speaker.speak(it.speech, Language.guess(it.speech)) }
                AlertControl.OPEN -> current?.let {
                    if (!AlertsStore.open(context, it.key)) state.say("This alert can't be opened from here")
                }
                AlertControl.DISMISS -> current?.let {
                    AlertsStore.dismiss(it.key)
                    state.say("Dismissed")
                }
                AlertControl.HOME -> state.go(Screen.Home)
                AlertControl.ALLOW -> state.start(AlertsStore.accessIntent(context), "Could not open the setting")
                AlertControl.APP_INFO -> state.start(AlertsStore.appInfoIntent(context), "Could not open app info")
            }
        }
    }

    when {
        !access -> ReaderLayout(
            state, ACCESS_HELP, { state.say(ACCESS_HELP) }, "Alerts", "Alerts need access",
            listOf(AlertControl.ALLOW, AlertControl.APP_INFO, AlertControl.HOME), tile = tile,
        )
        current == null -> ReaderLayout(
            state, "No new alerts", { state.say("No new alerts") }, "Alerts", "No new alerts",
            listOf(AlertControl.HOME), tile = tile,
        )
        else -> ReaderLayout(
            state = state,
            body = "${current.app}\n${current.shown}",
            onBodyTap = { state.speaker.speak(current.speech, Language.guess(current.speech)) },
            header = "Alert ${i + 1} of ${items.size}",
            headerSpoken = "Alert ${i + 1} of ${items.size}, from ${current.app}",
            controls = listOf(
                AlertControl.PREVIOUS, AlertControl.NEXT, AlertControl.READ,
                AlertControl.OPEN, AlertControl.DISMISS, AlertControl.HOME,
            ),
            tile = tile,
        )
    }
}
