package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.model.TimerWords
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.netgician.rplauncher.model.CameraMath
import com.netgician.rplauncher.model.Language
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.after
import com.netgician.rplauncher.mms.CalendarInvite
import com.netgician.rplauncher.mms.ContactCard
import com.netgician.rplauncher.mms.PartKind
import com.netgician.rplauncher.texts.Box
import com.netgician.rplauncher.texts.Conversation
import com.netgician.rplauncher.texts.DownloadState
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.PhotoFit
import com.netgician.rplauncher.texts.Photos
import com.netgician.rplauncher.texts.QuickReplies
import com.netgician.rplauncher.texts.SendRecords
import com.netgician.rplauncher.texts.TextMessage
import com.netgician.rplauncher.texts.TextNotifications
import com.netgician.rplauncher.texts.TextPart
import com.netgician.rplauncher.texts.TextSender
import com.netgician.rplauncher.texts.TextSpeech
import com.netgician.rplauncher.texts.TextStore
import com.netgician.rplauncher.texts.TextsBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** One big control on a texting screen. */
internal class Control(
    val label: String,
    val spoken: String = label,
    val icon: TileIcon? = null,
    val emphasized: Boolean = false,
    val run: () -> Unit,
)

@Composable
internal fun ControlTile(state: AppState, c: Control, m: Modifier, pos: TilePos) {
    Tile(c.label, m, spoken = c.spoken, icon = c.icon, emphasized = c.emphasized, onLongPress = longPress(state, c.spoken, pos)) { c.run() }
}

/** A plain list of choices, one big row each, like the app list. */
@Composable
internal fun ChoiceList(state: AppState, title: String, spokenTitle: String = title, choices: List<Control>) {
    InWindow(state.config.window) {
        PagedTiles(
            items = choices,
            page = state.listPage,
            onPage = { p, n ->
                state.listPage = p
                state.onPageTurned(p, n)
            },
            cols = 1,
            rows = state.config.rows + 1,
            onSpeak = state::say,
            header = { m -> SpokenText(title, m, state::say, spoken = spokenTitle) },
        ) { c, m, pos -> ControlTile(state, c, m, pos) }
    }
}

private val HOME_ICON = TileIcon.Vector(Icons.Filled.Home)
private val SEND_ICON = TileIcon.Vector(Icons.AutoMirrored.Filled.Send)

// ==================================================================== conversations

@Composable
fun TextsScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    // Shown at once from what's already loaded; refreshed behind it.
    val list = t.conversations
    LaunchedEffect(Unit) { t.refreshCache() }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            t.names.forget()
            TextsBus.changed()
        }
    }
    LaunchedEffect(Unit) {
        if (!t.names.canRead && !t.askedContacts) {
            t.askedContacts = true
            state.say(tr("To show names instead of numbers, allow contacts."))
            runCatching { contacts.launch(Manifest.permission.READ_CONTACTS) }
        }
    }
    val loaded = list
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(loaded != null) {
        if (loaded != null && !announced) {
            announced = true
            val n = loaded.sumOf { it.unread }
            state.speaker.speak(
                when {
                    loaded.isEmpty() -> tr("Texts. No conversations yet. Double tap New text to start one.")
                    n == 0 -> tr("Texts")
                    n == 1 -> tr("Texts, 1 new")
                    else -> tr("Texts, %s new", n)
                },
                flush = false,
            )
        }
    }
    val choices = buildList {
        add(Control(tr("Home"), icon = HOME_ICON) { state.go(Screen.Home) })
        add(Control(tr("New text"), spoken = tr("Start a new text"), icon = TileIcon.Vector(Icons.Filled.Add)) { t.startNew() })
        loaded.orEmpty().forEach { c ->
            val label = if (c.unread > 0) "${c.title} (${c.unread})" else c.title
            add(Control(label, spoken = TextSpeech.conversation(c), emphasized = c.unread > 0 || c.hasProblem) {
                state.go(Screen.Conversation(c.threadId))
            })
        }
    }
    ChoiceList(state, if (loaded == null) tr("Texts…") else tr("Texts"), choices = choices)
}

/** A conversation's texts and people: from memory at once if seen before, then fresh. Marks new texts read. */
@Composable
private fun rememberThread(state: AppState, threadId: Long, limit: Int = 60): ThreadData? {
    val context = LocalContext.current
    val t = state.texts
    val version by TextsBus.version.collectAsState()
    var loaded by remember(threadId) { mutableStateOf(t.cachedThread(threadId)) }
    LaunchedEffect(threadId, version, limit) {
        loaded = withContext(Dispatchers.IO) { t.loadThread(threadId, limit) }
        t.refreshUnread()
    }
    DisposableEffect(threadId) {
        TextsBus.openThread = threadId
        TextNotifications.cancel(context, threadId)
        onDispose { if (TextsBus.openThread == threadId) TextsBus.openThread = null }
    }
    return loaded
}

/** The words shown for a text: what it says, or what it holds, and how sending went. */
private fun cardWords(m: TextMessage): String = buildString {
    append(
        when {
            m.box == Box.WAITING -> tr("Picture text ") + when (m.download) {
                DownloadState.FAILED -> tr("didn't download")
                DownloadState.RETRYING -> tr("will try again soon")
                DownloadState.NOT_STARTED -> tr("waiting")
                DownloadState.DOWNLOADING -> tr("downloading…")
            }
            m.body.isNotBlank() -> m.body.trim()
            else -> TextSpeech.attachments(m.parts).ifBlank { tr("No words") }
        },
    )
    if (m.body.isNotBlank() && m.parts.isNotEmpty()) append("\n").append(TextSpeech.attachments(m.parts))
}

/** "NOT SENT", "Sending…", "Delivered", or nothing. */
private fun cardStatus(m: TextMessage): String? = when {
    m.box == Box.FAILED -> tr("NOT SENT")
    m.box == Box.SENDING -> tr("Sending…")
    m.outgoing && m.delivered -> tr("Delivered")
    else -> null
}

/** Says one text: who and when in the phone's voice, the words in their own language's voice, then the rest. */
private fun speakText(state: AppState, context: Context, m: TextMessage, group: Boolean, flush: Boolean = true) {
    val fromName = m.from?.takeIf { it.isNotBlank() }?.let(state.texts.names::of)
    val whenText = TextSpeech.whenSaid(m.date, System.currentTimeMillis()) { DateFormat.getTimeFormat(context).format(Date(it)) }
    val spoken = TextSpeech.spoken(m, fromName, group, whenText)
    state.speaker.speak(spoken.head, flush = flush)
    if (spoken.body.isNotBlank()) state.speaker.speak(spoken.body, Language.guess(spoken.body), flush = false)
    if (spoken.tail.isNotBlank()) state.speaker.speak(spoken.tail, flush = false)
}

/**
 * One text in the conversation view: who, when, and the words, large. It responds like a tile:
 * a tap opens it (or, with touch to hear, a tap reads it and a double tap opens it).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    state: AppState,
    m: TextMessage,
    who: String,
    time: String,
    spoken: String,
    modifier: Modifier,
    onRead: () -> Unit,
    onOpen: () -> Unit,
) {
    val lv = LocalLv.current
    val tap = LocalTapMode.current
    val view = androidx.compose.ui.platform.LocalView.current
    val shape = RoundedCornerShape(12.dp)
    val heardNow = tap.touchToHear && tap.heard == spoken
    val status = cardStatus(m)
    val press = if (tap.touchToHear) {
        Modifier.combinedClickable(
            onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                state.heard = spoken
                onRead()
            },
            onDoubleClick = { onOpen() },
            onLongClick = { onRead() },
        )
    } else {
        Modifier.combinedClickable(onClick = { onOpen() }, onLongClick = { onRead() })
    }
    // Your own texts sit to the right with a thin outline; theirs to the left with a thick one.
    Row(modifier) {
        if (m.outgoing) Spacer(Modifier.weight(0.12f))
        Column(
            modifier = Modifier
                .weight(0.88f)
                .fillMaxHeight()
                .clip(shape)
                .background(lv.tile)
                .border(BorderStroke(if (heardNow || m.box == Box.FAILED) 8.dp else if (m.outgoing) 2.dp else 4.dp, lv.accent), shape)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .then(press)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            FitText(
                text = if (status != null) "$who · $time · $status" else "$who · $time",
                color = lv.fg, maxSp = 18f * lv.textScale, minSp = 12f, maxLines = 1, align = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            FitText(
                text = cardWords(m),
                color = lv.fg, maxSp = 28f * lv.textScale, minSp = 14f, maxLines = 4, align = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (!m.outgoing) Spacer(Modifier.weight(0.12f))
    }
}

/**
 * A conversation: its last few texts, large, newest at the bottom, with Reply, Earlier and
 * All texts below. A text opens on its own for Try again, pictures, clips and Call.
 */
@Composable
fun ConversationScreen(state: AppState, threadId: Long) {
    val context = LocalContext.current
    val lv = LocalLv.current
    val t = state.texts
    var limit by remember(threadId) { mutableIntStateOf(60) }
    val data = rememberThread(state, threadId, limit)
    val msgs = data?.messages.orEmpty()
    val group = (data?.people?.size ?: 0) > 1
    val title = data?.people?.let { p -> People.join(p.map(t.names::of)) } ?: tr("Texts")
    // How many texts fit: the person's row setting, fewer if they'd be too small.
    var fits by remember { mutableIntStateOf(state.config.rows.coerceIn(2, 5)) }
    var back by remember(threadId) { mutableIntStateOf(0) } // pages back from the newest
    val pageCount = maxOf(1, (msgs.size + fits - 1) / fits)
    val page = back.coerceIn(0, pageCount - 1)
    val endIndex = (msgs.size - page * fits).coerceAtLeast(0)
    val shownMsgs = msgs.subList((endIndex - fits).coerceAtLeast(0), endIndex)

    // On opening: the name, how many are new, then the new texts (or the newest one) read aloud.
    var greeted by remember(threadId) { mutableStateOf(false) }
    var newestKey by remember(threadId) { mutableStateOf<String?>(null) }
    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        val newest = msgs.lastOrNull()
        if (!greeted) {
            greeted = true
            newestKey = newest?.key
            val unread = msgs.filter { !it.read && !it.outgoing }
            state.speaker.speak(
                when {
                    msgs.isEmpty() -> tr("%s. No texts yet. Double tap Reply to write one.", title)
                    unread.isEmpty() -> "$title."
                    unread.size == 1 -> tr("%s. 1 new.", title)
                    else -> tr("%s. %s new.", title, unread.size)
                },
            )
            (unread.ifEmpty { listOfNotNull(newest) }).takeLast(5).forEach { speakText(state, context, it, group, flush = false) }
        } else if (newest != null && newest.key != newestKey) {
            // A new text while the conversation is open: show it and read it, if it's theirs.
            newestKey = newest.key
            back = 0
            if (!newest.outgoing) speakText(state, context, newest, group, flush = false)
        }
    }

    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpokenText(
                if (pageCount > 1 && page > 0) tr("%s · earlier", title) else title,
                Modifier.fillMaxWidth().height((40f * lv.textScale).dp), state::say,
                spoken = tr("Conversation with %s", title), maxSp = 24f,
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Each text gets at least 80 dp (more with bigger text); fewer texts show if the window is small.
                val room = (maxHeight.value / ((80f * state.config.textScale) + 8f)).toInt().coerceIn(1, 5)
                LaunchedEffect(room) { fits = minOf(room, state.config.rows.coerceIn(2, 5)) }
                if (data == null) {
                    FitText(tr("Loading…"), lv.fg, maxSp = 26f * lv.textScale, modifier = Modifier.fillMaxWidth())
                } else if (msgs.isEmpty()) {
                    SpokenText(tr("No texts yet"), Modifier.fillMaxSize(), state::say, spoken = tr("No texts yet. Double tap Reply to write one."))
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                        shownMsgs.forEach { m ->
                            val who = if (m.outgoing) tr("You") else m.from?.takeIf { it.isNotBlank() }?.let(t.names::of) ?: tr("Unknown")
                            val time = TextSpeech.whenSaid(m.date, System.currentTimeMillis()) {
                                DateFormat.getTimeFormat(context).format(Date(it))
                            }.replace(tr(" at "), ", ")
                            val spoken = TextSpeech.message(m, if (m.outgoing) null else who, group, time)
                            MessageCard(
                                state, m, who, time, spoken,
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                                onRead = { speakText(state, context, m, group) },
                                onOpen = { state.go(Screen.Message(threadId, m.key)) },
                            )
                        }
                        // Keep cards the same size when the last page is short.
                        repeat(fits - shownMsgs.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height((80f * lv.textScale).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(tr("Reply"), Modifier.weight(1.3f).fillMaxHeight(), spoken = tr("Reply to %s", title), icon = SEND_ICON, emphasized = true) {
                    t.startReply(threadId)
                }
                if (page > 0) {
                    Tile(tr("Later"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Later texts")) {
                        back = page - 1
                        state.feel(com.netgician.rplauncher.model.Landmarks.PAGE_TURN)
                        if (page - 1 == 0) state.say(tr("Newest texts")) else state.say(tr("Later texts"))
                    }
                } else if (page < pageCount - 1 || msgs.size >= limit) {
                    Tile(tr("Earlier"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Earlier texts")) {
                        // The oldest loaded page: fetch more before going back.
                        if (page + 1 >= pageCount - 1 && msgs.size >= limit) limit += 60
                        back = page + 1
                        state.feel(com.netgician.rplauncher.model.Landmarks.PAGE_TURN)
                        state.say(tr("Earlier texts"))
                    }
                }
                Tile(tr("All texts"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Back to all texts")) { state.go(Screen.Texts) }
            }
            if (page > 0 && (page < pageCount - 1 || msgs.size >= limit)) {
                Tile(tr("Earlier"), Modifier.fillMaxWidth().height((56f * lv.textScale).dp), spoken = tr("Earlier texts")) {
                    if (page + 1 >= pageCount - 1 && msgs.size >= limit) limit += 60
                    back = page + 1
                    state.feel(com.netgician.rplauncher.model.Landmarks.PAGE_TURN)
                    state.say(tr("Earlier texts"))
                }
            }
        }
    }
}

/** One text on its own, read aloud, with what it needs: Try again, See picture, Play, Call... */
@Composable
fun MessageScreen(state: AppState, threadId: Long, key: String) {
    val context = LocalContext.current
    val t = state.texts
    val data = rememberThread(state, threadId, limit = maxOf(60, state.texts.cachedThread(threadId)?.messages?.size ?: 0))
    val m = data?.messages?.firstOrNull { it.key == key }
    if (data == null || m == null) {
        val words = if (data == null) tr("Loading…") else tr("This text is no longer here.")
        ReaderLayout(
            state = state, body = words, onBodyTap = { state.say(words) }, header = tr("Text"), headerSpoken = tr("Text"),
            controls = listOf(Control(tr("Back"), spoken = tr("Back to the conversation")) { state.go(Screen.Conversation(threadId)) }),
        ) { c, mod, pos -> ControlTile(state, c, mod, pos) }
        return
    }
    val group = data.people.size > 1
    val fromName = m.from?.takeIf { it.isNotBlank() }?.let(t.names::of)
    LaunchedEffect(m.key) { speakText(state, context, m, group) }

    val firstImage = m.parts.firstOrNull { it.kind == PartKind.IMAGE }
    val thumb by produceState<Bitmap?>(null, firstImage?.id) {
        value = firstImage?.let { p -> withContext(Dispatchers.IO) { Pictures.load(context, p.id, 900) } }
    }

    val extras = attachmentControls(state, threadId, m)
    val controls = buildList {
        when {
            m.box == Box.FAILED -> add(Control(tr("Try again"), spoken = tr("Try sending again"), emphasized = true) { t.retry(m) })
            m.box == Box.WAITING -> add(
                when (m.download) {
                    DownloadState.DOWNLOADING -> Control(tr("Downloading"), spoken = tr("Still downloading")) {
                        state.say(tr("Still downloading. You'll hear when it arrives."))
                    }
                    DownloadState.NOT_STARTED -> Control(tr("Download"), spoken = tr("Download the picture text"), emphasized = true) { t.retry(m) }
                    else -> Control(tr("Try again"), spoken = tr("Try downloading again"), emphasized = true) { t.retry(m) }
                },
            )
        }
        if (extras.size <= 2) addAll(extras) else add(
            Control(tr("Attachments (%s)", extras.size), spoken = tr("See all %s attachments", extras.size), emphasized = true) {
                t.attachmentsOf = m
                state.go(Screen.Attachments(threadId))
            },
        )
        add(Control(tr("Read again"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { speakText(state, context, m, group) })
        add(Control(tr("Reply"), icon = SEND_ICON) { t.startReply(threadId) })
        val callee = if (!m.outgoing && !m.from.isNullOrBlank()) m.from else data.people.singleOrNull()
        callee?.let { number ->
            val name = t.names.of(number)
            add(Control(tr("Call"), spoken = tr("Call %s", name), icon = TileIcon.Vector(Icons.Filled.Call)) {
                state.go(Screen.ConfirmCall(TileSpec.Contact(name, number)))
            })
        }
        add(Control(tr("Back"), spoken = tr("Back to the conversation")) { state.go(Screen.Conversation(threadId)) })
    }

    val who = if (m.outgoing) tr("You") else fromName ?: tr("Unknown")
    val shown = "$who: " + cardWords(m) + (cardStatus(m)?.let { "\n$it" } ?: "")
    val lv = LocalLv.current
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = { speakText(state, context, m, group) },
        header = who,
        headerSpoken = tr("Text from %s", who),
        controls = controls,
        controlsHeader = false,
        bodyContent = {
            val b = thumb
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FitText(
                    text = shown, color = lv.fg, maxSp = 30f * lv.textScale, minSp = 14f,
                    maxLines = if (b != null) 3 else 8, align = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1.4f),
                    )
                }
            }
        },
    ) { c, mod, pos -> ControlTile(state, c, mod, pos) }
}

/** One control for each thing attached to [m]: pictures together, then each clip, video, card, invite and file. */
@Composable
private fun attachmentControls(state: AppState, threadId: Long, m: TextMessage): List<Control> {
    val t = state.texts
    return buildList {
        val images = m.parts.filter { it.kind == PartKind.IMAGE }
        if (images.isNotEmpty()) {
            add(Control(if (images.size == 1) tr("See picture") else tr("See pictures"), spoken = tr("See the picture bigger"), emphasized = true) {
                state.go(Screen.Picture(threadId, images.map { it.id }, 0))
            })
        }
        m.parts.filter { it.kind == PartKind.AUDIO }.forEach { a ->
            val playing = t.playingPart == a.id
            add(Control(if (playing) tr("Stop") else tr("Play"), spoken = if (playing) tr("Stop the clip") else tr("Play the clip"),
                icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) { t.play(a) })
        }
        m.parts.filter { it.kind == PartKind.VIDEO }.forEach { v ->
            add(Control(tr("Play video"), spoken = tr("Play the video"), icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) {
                t.openElsewhere(v)
            })
        }
        m.parts.filter { it.kind == PartKind.CONTACT }.forEach { c ->
            add(Control(tr("Contact card"), spoken = tr("Open the contact card"), emphasized = true) { state.go(Screen.Card(threadId, c.id)) })
        }
        m.parts.filter { it.kind == PartKind.EVENT }.forEach { e ->
            add(Control(tr("Invite"), spoken = tr("Open the calendar invite"), emphasized = true) { state.go(Screen.Invite(threadId, e.id)) })
        }
        m.parts.filter { it.kind == PartKind.OTHER }.forEach { o ->
            add(Control(tr("Open file"), spoken = tr("Open the file in another app")) { t.openElsewhere(o) })
        }
    }
}

/** Everything attached to one text, one big row each. */
@Composable
fun AttachmentsScreen(state: AppState, threadId: Long) {
    val m = state.texts.attachmentsOf
    if (m == null) {
        LaunchedEffect(Unit) { state.go(Screen.Conversation(threadId)) }
        return
    }
    LaunchedEffect(Unit) { state.say(tr("Attachments. %s", TextSpeech.attachments(m.parts))) }
    ChoiceList(
        state, tr("Attachments"),
        choices = listOf(Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Conversation(threadId)) }) +
            attachmentControls(state, threadId, m),
    )
}

// ==================================================================== writing

@Composable
fun ComposeScreen(state: AppState) {
    val t = state.texts
    val d = t.draft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.Texts) }
        return
    }
    var typing by remember { mutableStateOf(false) }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val words = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        val cur = t.draft
        if (r.resultCode == Activity.RESULT_OK && !words.isNullOrBlank() && cur != null) {
            val text = if (cur.text.isBlank()) words.replaceFirstChar { it.uppercase() } else cur.text.trimEnd() + " " + words
            t.draft = cur.copy(text = text)
            state.say(tr("Your text: %s. Double tap Send to send it, or Speak to add more.", text))
        } else {
            state.say(tr("Nothing heard. Double tap Speak to try again."))
        }
    }
    val who = People.join(d.to.map(t.names::of))
    val attached = TextSpeech.attachments(d.files.map { TextPart(0, it.kind, it.contentType, null, "") })
    val shown = d.text.ifBlank { tr("Nothing written yet.") } + if (attached.isNotEmpty()) tr("\nWith: %s", attached.removeSuffix(".").lowercase()) else ""
    val readBack = {
        state.say(
            if (d.text.isBlank() && d.files.isEmpty()) tr("Nothing written yet. Double tap Speak to say your message.")
            else tr("Text to %s. %s %s", who, d.text, attached).trim(),
        )
    }

    if (typing) {
        TypingBox(state, d.text) { typed ->
            t.draft = t.draft?.copy(text = typed)
            typing = false
            state.say(if (typed.isBlank()) tr("Nothing typed.") else tr("Your text: %s. Double tap Send to send it.", typed))
        }
        return
    }

    val controls = buildList {
        add(Control(tr("Speak"), spoken = tr("Speak your message")) {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Say your message"))
            state.speaker.stop()
            try {
                dictate.launch(i)
            } catch (e: ActivityNotFoundException) {
                state.say(tr("Speech typing isn't available on this phone. Use Keyboard or Quick replies."))
            }
        })
        add(Control(tr("Send"), spoken = tr("Send to %s", who), icon = SEND_ICON, emphasized = true) { t.send() })
        add(Control(tr("Quick replies"), spoken = tr("Choose a quick reply")) { state.go(Screen.QuickReply) })
        add(Control(tr("Keyboard"), spoken = tr("Type with the keyboard")) { typing = true })
        add(Control(tr("More"), spoken = tr("More: add a photo, a voice message or a person")) { state.go(Screen.ComposeMore) })
        add(Control(tr("Cancel"), spoken = tr("Cancel this text"), icon = TileIcon.Vector(Icons.Filled.Close)) { t.cancelDraft() })
    }
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = readBack,
        header = tr("To %s", who),
        headerSpoken = tr("Text to %s", who),
        controls = controls,
        bodyWeight = 0.45f,
        controlsHeader = false,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}

/** The less-used writing choices, so the writing screen keeps to one page. */
@Composable
fun ComposeMoreScreen(state: AppState) {
    val t = state.texts
    val d = t.draft
    if (d == null) {
        LaunchedEffect(Unit) { state.go(Screen.Texts) }
        return
    }
    LaunchedEffect(Unit) { state.say(tr("More")) }
    val who = People.join(d.to.map(t.names::of))
    val attached = TextSpeech.attachments(d.files.map { TextPart(0, it.kind, it.contentType, null, "") })
    ChoiceList(
        state, tr("More"),
        choices = buildList {
            add(Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Compose) })
            add(Control(tr("Add photo"), spoken = tr("Add a photo or video")) { state.go(Screen.AddPhoto) })
            add(Control(tr("Voice message"), spoken = tr("Record a voice message")) { state.go(Screen.Record) })
            add(Control(tr("Add a person"), spoken = tr("Add a person to this text")) {
                t.addingPerson = true
                state.go(Screen.NewText)
            })
            add(Control(tr("Read it back"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) {
                state.say(
                    if (d.text.isBlank() && d.files.isEmpty()) tr("Nothing written yet.")
                    else tr("Text to %s. %s %s", who, d.text, attached).trim(),
                )
            })
            if (d.text.isNotBlank() || d.files.isNotEmpty()) {
                add(Control(tr("Clear"), spoken = tr("Clear what's written")) {
                    t.draft = d.copy(text = "", files = emptyList())
                    state.say(tr("Cleared"))
                    state.go(Screen.Compose)
                })
            }
        },
    )
}

/** Big-text typing. The keyboard's Done key, or the Done typing tile, finishes. */
@Composable
private fun TypingBox(state: AppState, initial: String, onDone: (String) -> Unit) {
    val lv = LocalLv.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        state.say(tr("Type your message. Press Done on the keyboard when you're finished."))
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val finish = {
        keyboard?.hide()
        onDone(value.text.trim())
    }
    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(BorderStroke(4.dp, lv.accent), RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .focusRequester(focus),
                textStyle = TextStyle(
                    color = lv.fg,
                    fontSize = (30f * lv.textScale).sp,
                    lineHeight = (34f * lv.textScale).sp,
                    fontFamily = lv.font,
                    fontWeight = FontWeight.Bold,
                ),
                cursorBrush = SolidColor(lv.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
            )
            Tile(tr("Done typing"), Modifier.fillMaxWidth().height((64f * lv.textScale).dp), emphasized = true) { finish() }
        }
    }
}

@Composable
fun QuickReplyScreen(state: AppState) {
    val t = state.texts
    LaunchedEffect(Unit) { state.say(tr("Quick replies")) }
    val replies = QuickReplies.forLanguage(com.netgician.rplauncher.i18n.Lang.code)
    val choices = listOf(Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Compose) }) +
        replies.map { r ->
            Control(r) {
                t.draft = t.draft?.copy(text = r)
                state.go(Screen.Compose)
                state.say(tr("%s. Double tap Send to send it.", r))
            }
        }
    ChoiceList(state, tr("Quick replies"), choices = choices)
}

@Composable
fun NewTextScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        val c = readPhoneContact(context, uri)
        if (c == null) state.say(tr("Couldn't read that contact")) else t.addPerson(c.number)
    }
    val first = t.draft?.to.isNullOrEmpty()
    LaunchedEffect(Unit) {
        state.say(if (first) tr("New text. Who is it to? Pick a contact, or type a number.") else tr("Add a person. Pick a contact, or type a number."))
    }
    ChoiceList(
        state, if (first) tr("Who to?") else tr("Add a person"),
        choices = listOf(
            Control(tr("Back")) { state.back() },
            Control(tr("Pick a contact"), icon = TileIcon.Vector(Icons.Filled.Person)) {
                runCatching {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }.onFailure { state.say(tr("No contacts app found")) }
            },
            Control(tr("Type a number"), spoken = tr("Type a phone number")) { state.go(Screen.NumberPad(NumberPurpose.TEXT)) },
        ),
    )
}

/** A big number pad. Each digit is said aloud as it's pressed. */
@Composable
fun NumberPadScreen(state: AppState, purpose: NumberPurpose) {
    val context = LocalContext.current
    var number by remember(purpose) { mutableStateOf(if (purpose == NumberPurpose.TIMER) StoreShots.takeTypedTime() else "") }
    LaunchedEffect(purpose) {
        state.say(
            when (purpose) {
                NumberPurpose.OWN_NUMBER -> tr("Type this phone's own number")
                NumberPurpose.VOICEMAIL -> tr("Type the voicemail number. Your carrier can tell you what it is.")
                NumberPurpose.TIMER -> tr("Type the minutes and seconds. The last two numbers are the seconds.")
                NumberPurpose.TEXT -> tr("Type the phone number")
            },
        )
    }
    // Voicemail numbers can need star, hash or plus (*86, say).
    val keys = if (purpose == NumberPurpose.VOICEMAIL) {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#", "delete", "+", "done")
    } else {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "done")
    }
    InWindow(state.config.window) {
        PagedTiles(
            items = keys,
            page = 0,
            onPage = { _, _ -> },
            cols = 3,
            rows = keys.size / 3,
            onSpeak = state::say,
            header = { m ->
                if (purpose == NumberPurpose.TIMER) {
                    // A min box and a sec box, filled as it's typed: 1, 3, 0 shows 1 min 30 sec.
                    TimerEntryBoxes(number, m, state::say)
                } else SpokenText(
                    number.ifEmpty { tr("Type the number") }, m, state::say,
                    spoken = if (number.isEmpty()) tr("Nothing typed yet") else People.spoken(number),
                )
            },
            minTarget = 0f,
            // The timer's min and sec boxes get a taller row, so the numbers can be big.
            headerHeight = if (purpose == NumberPurpose.TIMER) 120f * state.config.textScale else null,
        ) { key, m, _ ->
            when (key) {
                "delete" -> if (number.isEmpty()) {
                    Tile(tr("Back"), m) { state.back() }
                } else {
                    Tile(tr("Delete"), m, spoken = tr("Delete the last digit")) {
                        number = number.dropLast(1)
                        val ms = if (purpose == NumberPurpose.TIMER) TimerRules.typed(number) else null
                        state.say(if (ms != null) tr("Deleted. %s", TimerWords.length(ms)) else tr("Deleted"))
                    }
                }
                "done" -> Tile(if (purpose == NumberPurpose.TIMER) tr("Start") else tr("Done"), m, emphasized = true) {
                    if (purpose == NumberPurpose.TIMER) {
                        val ms = TimerRules.typed(number)
                        if (ms == null) state.say(tr("Type the minutes and seconds first"))
                        else startTimer(state, ms)
                    } else if (number.length < 3) {
                        state.say(tr("Type the whole number first"))
                    } else if (purpose == NumberPurpose.OWN_NUMBER) {
                        SendRecords.learnNumber(context, number)
                        state.say(tr("Saved. This phone's number is %s.", People.spoken(number)))
                        state.go(Screen.TextsCheck)
                    } else if (purpose == NumberPurpose.VOICEMAIL) {
                        state.update { it.copy(voicemailNumber = number) }
                        state.say(tr("Saved. Voicemail now calls %s.", voicemailSpoken(number)))
                        state.go(Screen.VoicemailSetup)
                    } else {
                        state.texts.addPerson(number)
                    }
                }
                else -> Tile(key, m, spoken = com.netgician.rplauncher.calls.CallWords.key(key)) {
                    if (purpose == NumberPurpose.TIMER) {
                        // Says the whole time so far, so it's clear how the digits are read.
                        if (number.length < TimerRules.MAX_DIGITS && !(number.isEmpty() && key == "0")) {
                            number += key
                            state.say(TimerRules.typed(number)?.let { TimerWords.length(it) } ?: tr("No time yet"))
                        }
                    } else if (number.length < 15) {
                        number += key
                        state.say(com.netgician.rplauncher.calls.CallWords.key(key))
                    }
                }
            }
        }
    }
}

/** Gets a picked or taken photo (or a short video) ready to send: small enough for the carrier. */
private fun prepareAttachment(context: Context, uri: Uri, typeHint: String?, usedBytes: Int): Pair<DraftFile?, String> {
    val type = (typeHint ?: context.contentResolver.getType(uri) ?: "image/jpeg").lowercase()
    val budget = PhotoFit.budget(TextSender.maxMmsBytes(context), usedBytes)
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    if (type.startsWith("image/")) {
        val bytes = Photos.fit(context, uri, budget) ?: return null to tr("That picture couldn't be read.")
        val f = File(dir, "photo-${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        return DraftFile(f.absolutePath, "image/jpeg") to tr("Picture added.")
    }
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return null to tr("That file couldn't be read.")
    if (bytes.size > budget) {
        val what = if (type.startsWith("video/")) tr("video") else tr("file")
        return null to tr("That %s is too big to send as a picture text. Try a shorter one.", what)
    }
    val ext = when {
        type == "video/mp4" -> "mp4"
        type.startsWith("video/3gpp") -> "3gp"
        type.startsWith("video/") -> "mp4"
        else -> "bin"
    }
    val f = File(dir, "file-${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
    return DraftFile(f.absolutePath, type) to (if (type.startsWith("video/")) tr("Video added.") else tr("File added."))
}

@Composable
fun AddPhotoScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun attach(uri: Uri, typeHint: String?) {
        state.say(tr("Getting it ready."))
        scope.launch {
            val used = t.draft?.files?.sumOf { File(it.path).length().toInt() } ?: 0
            val (file, words) = withContext(Dispatchers.IO) { prepareAttachment(context, uri, typeHint, used) }
            if (file != null) {
                t.draft = t.draft?.let { it.copy(files = it.files + file) }
                state.go(Screen.Compose)
                state.say(tr("%s Double tap Send to send it.", words))
            } else {
                state.say(words)
            }
        }
    }

    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) attach(u, "image/jpeg") else state.say(tr("No photo taken"))
    }
    fun openCamera() {
        val f = File(context.cacheDir, "camera/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val u = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        cameraUri = u
        runCatching { take.launch(u) }.onFailure { state.say(tr("No camera app found")) }
    }
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else state.say(tr("The camera is not allowed. You can allow it in the phone's settings."))
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attach(uri, null) else state.say(tr("Nothing chosen"))
    }
    LaunchedEffect(Unit) { state.say(tr("Add a photo. Take one now, or choose one you have.")) }
    ChoiceList(
        state, tr("Add a photo"),
        choices = listOf(
            Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Compose) },
            Control(tr("Take a photo"), spoken = tr("Take a photo with the camera")) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) openCamera() else cameraAllowed.launch(Manifest.permission.CAMERA)
            },
            Control(tr("Choose a photo"), spoken = tr("Choose a photo or video you already have")) {
                runCatching {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }.onFailure { state.say(tr("No photo picker found")) }
            },
        ),
    )
}

/** Records a voice message (up to two minutes), then plays it back or sends it. */
@Composable
fun RecordScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopRecording(said: String? = null) {
        val r = recorder ?: return
        runCatching { r.stop() }.onFailure { file?.delete(); file = null }
        r.release()
        recorder = null
        state.say(said ?: if (file != null) tr("Recorded %s seconds. Double tap Send it, or Play it back to listen.", seconds) else tr("Nothing was recorded. Try again."))
    }

    fun startRecording() {
        state.speaker.stop()
        player?.release(); player = null
        val f = File(context.cacheDir, "shared/voice-${System.currentTimeMillis()}.m4a").apply { parentFile?.mkdirs() }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(120_000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(tr("Two minutes reached. Recording stopped. Double tap Send it, or Play it back."))
            }
            r.prepare()
            r.start()
            file?.delete()
            file = f
            seconds = 0
            recorder = r
            state.vibes.play(longArrayOf(0, 150))
        } catch (e: Exception) {
            r.release()
            state.say(tr("The microphone couldn't start."))
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else state.say(tr("The microphone is not allowed. You can allow it in the phone's settings."))
    }
    val recording = recorder != null
    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            seconds++
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder?.let { runCatching { it.stop() }; it.release() }
            player?.release()
        }
    }
    LaunchedEffect(Unit) { state.say(tr("Voice message. Double tap Start recording, then speak. A buzz means it's recording.")) }

    val f = file
    val controls = when {
        recording -> listOf(Control(tr("Stop recording"), spoken = tr("Stop recording"), emphasized = true) { stopRecording() })
        f == null -> listOf(
            Control(tr("Start recording"), spoken = tr("Start recording"), emphasized = true) {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (ok) startRecording() else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Compose) },
        )
        else -> listOf(
            Control(tr("Send it"), spoken = tr("Send the voice message"), icon = SEND_ICON, emphasized = true) {
                t.draft = t.draft?.let { it.copy(files = it.files + DraftFile(f.absolutePath, "audio/mp4")) }
                file = null
                t.send()
            },
            Control(tr("Play it back"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) {
                player?.release()
                player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(f.absolutePath)
                        setOnCompletionListener { state.say(tr("End of recording")) }
                        prepare()
                        start()
                    }
                }.getOrNull()
            },
            Control(tr("Record again")) {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (ok) startRecording() else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            Control(tr("Cancel"), spoken = tr("Throw this recording away"), icon = TileIcon.Vector(Icons.Filled.Close)) {
                f.delete()
                file = null
                state.go(Screen.Compose)
                state.say(tr("Recording thrown away"))
            },
        )
    }
    val shown = when {
        recording -> tr("Recording… %s s", seconds)
        f != null -> tr("Recorded %s seconds", seconds)
        else -> tr("Ready to record")
    }
    ReaderLayout(
        state = state, body = shown, onBodyTap = { if (!recording) state.say(shown) },
        header = tr("Voice message"), headerSpoken = tr("Voice message"), controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}

// ==================================================================== attachments

/** Loads a picture from a picture text, no bigger than [maxSide] on its long side. */
internal object Pictures {
    fun load(context: Context, partId: Long, maxSide: Int): Bitmap? = runCatching {
        if (Demo.on && partId == Demo.PICTURE_ID) return Demo.picture(maxSide)
        val bytes = TextStore(context).readPart(partId) ?: return null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) decoder.setTargetSampleSize(ceil(long / maxSide.toFloat()).toInt())
        }
    }.getOrNull()
}

/** A picture from a text, filling the window: zoom, colours, and reading any words in it. */
@Composable
fun PictureScreen(state: AppState, screen: Screen.Picture) {
    val context = LocalContext.current
    val lv = LocalLv.current
    val partId = screen.partIds.getOrNull(screen.index) ?: screen.partIds.first()
    val count = screen.partIds.size
    val bitmap by produceState<Bitmap?>(null, partId) {
        value = withContext(Dispatchers.IO) { Pictures.load(context, partId, 2048) }
    }
    var scale by remember(partId) { mutableFloatStateOf(1f) }
    var offset by remember(partId) { mutableStateOf(Offset.Zero) }
    var filter by remember { mutableStateOf(CameraMath.Filter.NORMAL) }
    var reading by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(partId) {
        val which = if (count > 1) tr("Picture %s of %s.", screen.index + 1, count) else tr("Picture.")
        state.say(tr("%s Closer and Wider zoom. Read text reads any words in it.", which))
    }
    val controls = buildList {
        add(Control(tr("Closer"), spoken = tr("Zoom closer")) {
            scale = CameraMath.closer(scale, CameraMath.FROZEN_MAX_ZOOM)
            state.say(CameraMath.describeZoom(scale))
        })
        add(Control(tr("Wider"), spoken = tr("Zoom wider")) {
            scale = CameraMath.wider(scale, 1f)
            if (scale <= 1f) offset = Offset.Zero
            state.say(CameraMath.describeZoom(scale))
        })
        add(Control(if (reading) tr("Reading…") else tr("Read text"), spoken = tr("Read any words in the picture"), emphasized = true) {
            val b = bitmap
            if (b == null || reading) return@Control
            reading = true
            state.say(tr("Reading"))
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCatching { state.ocr().read(b.toRgbImage()) }.getOrNull() }
                reading = false
                if (result == null) state.say(tr("The words couldn't be read.")) else state.showRead(result, backTo = screen)
            }
        })
        add(Control(filter.label, spoken = tr("Colours: %s", filter.label)) {
            filter = CameraMath.Filter.entries.after(filter)
            state.say(filter.label)
        })
        if (count > 1) {
            add(Control(tr("Next picture")) {
                state.go(screen.copy(index = (screen.index + 1) % count))
            })
        }
        add(Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Conversation(screen.threadId)) })
    }
    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .semantics { contentDescription = tr("Picture, %s", CameraMath.describeZoom(scale)) },
                contentAlignment = Alignment.Center,
            ) {
                val b = bitmap
                if (b == null) {
                    FitText(tr("Loading…"), lv.fg, maxSp = 24f * lv.textScale)
                } else {
                    val matrix = CameraMath.matrix(filter)
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .pointerInput(partId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, CameraMath.FROZEN_MAX_ZOOM)
                                    offset = if (scale <= 1f) Offset.Zero else offset + pan * scale
                                }
                            },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(0.38f)) {
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
                    header = { m ->
                        val status = (if (count > 1) tr("%s of %s · ", screen.index + 1, count) else "") + CameraMath.shortZoom(scale)
                        SpokenText(status, m, state::say, spoken = CameraMath.describeZoom(scale), maxSp = 22f)
                    },
                ) { c, m, pos -> ControlTile(state, c, m, pos) }
            }
        }
    }
}

/** A contact card from a text: read aloud, with Call, Text and Save. */
@Composable
fun CardScreen(state: AppState, screen: Screen.Card) {
    val context = LocalContext.current
    val cards by produceState<List<ContactCard>?>(null, screen.partId) {
        value = withContext(Dispatchers.IO) {
            runCatching { TextStore(context).readPart(screen.partId)?.let { ContactCard.read(String(it, Charsets.UTF_8)) } }
                .getOrNull().orEmpty()
        }
    }
    var index by remember { mutableIntStateOf(0) }
    val list = cards
    val back = Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Conversation(screen.threadId)) }
    if (list == null || list.isEmpty()) {
        val words = if (list == null) tr("Loading…") else tr("This contact card is empty or damaged.")
        LaunchedEffect(list) { if (list != null) state.say(words) }
        ReaderLayout(state, words, { state.say(words) }, tr("Contact card"), tr("Contact card"), listOf(back)) { c, m, pos -> ControlTile(state, c, m, pos) }
        return
    }
    val card = list[index.coerceIn(0, list.lastIndex)]
    LaunchedEffect(card) { state.say(card.speech) }
    val shown = buildString {
        append(card.name.ifBlank { tr("No name") })
        card.organization?.let { append("\n").append(it) }
        card.phones.forEach { append("\n${it.kind}: ${it.number}") }
        card.emails.forEach { append("\n").append(it) }
        card.address?.let { append("\n").append(it) }
    }
    val controls = buildList {
        card.phones.forEachIndexed { n, p ->
            val label = if (card.phones.size == 1) tr("Call") else tr("Call %s", p.kind.lowercase())
            add(Control(label, spoken = tr("Call %s %s", card.name, p.kind.lowercase()), icon = TileIcon.Vector(Icons.Filled.Call), emphasized = n == 0) {
                state.go(Screen.ConfirmCall(TileSpec.Contact(card.name.ifBlank { p.number }, p.number)))
            })
        }
        card.phones.firstOrNull()?.let { p ->
            add(Control(tr("Text"), spoken = tr("Send %s a text", card.name), icon = SEND_ICON) {
                state.texts.draft = Draft(to = listOf(p.number))
                state.go(Screen.Compose)
                state.say(tr("Text to %s.", card.name))
            })
        }
        add(Control(tr("Save to contacts"), spoken = tr("Save %s to contacts", card.name)) {
            val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, card.name)
            card.phones.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it.number) }
            card.phones.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it.number) }
            card.emails.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            card.organization?.let { i.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            card.address?.let { i.putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
            state.start(i, tr("No contacts app found"))
        })
        add(Control(tr("Read again"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(card.speech) })
        if (list.size > 1) add(Control(tr("Next card")) { index = (index + 1) % list.size })
        add(back)
    }
    ReaderLayout(
        state, shown, { state.say(card.speech) },
        header = if (list.size > 1) tr("Card %s of %s", index + 1, list.size) else tr("Contact card"),
        headerSpoken = tr("Contact card for %s", card.name),
        controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}

/** A calendar invite from a text: read aloud, with Add to calendar. */
@Composable
fun InviteScreen(state: AppState, screen: Screen.Invite) {
    val context = LocalContext.current
    val events by produceState<List<CalendarInvite>?>(null, screen.partId) {
        value = withContext(Dispatchers.IO) {
            runCatching { TextStore(context).readPart(screen.partId)?.let { CalendarInvite.read(String(it, Charsets.UTF_8)) } }
                .getOrNull().orEmpty()
        }
    }
    var index by remember { mutableIntStateOf(0) }
    val list = events
    val back = Control(tr("Back"), spoken = tr("Back to the text")) { state.go(Screen.Conversation(screen.threadId)) }
    if (list == null || list.isEmpty()) {
        val words = if (list == null) tr("Loading…") else tr("This invite is empty or damaged.")
        LaunchedEffect(list) { if (list != null) state.say(words) }
        ReaderLayout(state, words, { state.say(words) }, tr("Invite"), tr("Calendar invite"), listOf(back)) { c, m, pos -> ControlTile(state, c, m, pos) }
        return
    }
    val e = list[index.coerceIn(0, list.lastIndex)]
    val speech = e.speech()
    LaunchedEffect(e) { state.say(speech) }
    val shown = buildString {
        append(e.title.ifBlank { tr("No title") })
        e.startMillis?.let { append("\n").append(e.whenText(it)) }
        e.location?.let { append("\n").append(it) }
        e.description?.let { append("\n").append(it) }
    }
    val controls = buildList {
        add(Control(tr("Add to calendar"), spoken = tr("Add %s to the calendar", e.title), emphasized = true) {
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, e.title)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
            e.startMillis?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            e.endMillis?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            e.location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            e.description?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            state.start(i, tr("No calendar app found"))
        })
        add(Control(tr("Read again"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(speech) })
        if (list.size > 1) add(Control(tr("Next event")) { index = (index + 1) % list.size })
        add(back)
    }
    ReaderLayout(
        state, shown, { state.say(speech) },
        header = if (list.size > 1) tr("Invite %s of %s", index + 1, list.size) else tr("Invite"),
        headerSpoken = tr("Calendar invite"),
        controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}

/**
 * The Texts tile before RP Launcher is the texting app: say which app texts go to now,
 * and let the person choose, instead of jumping into another app.
 */
@Composable
fun TextsNotSetScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val current = remember { com.netgician.rplauncher.texts.TextRole.currentAppName(context) }
    val words = if (current == null) {
        tr("RP Launcher isn't the texting app yet, and no texting app is set on this phone right now. Choose Use RP Launcher for texts to set it up.")
    } else {
        tr("RP Launcher isn't the texting app yet. Right now, texts go to %s. Choose Use RP Launcher for texts to have them here instead, or keep using %s.", current, current)
    }
    LaunchedEffect(Unit) { state.say(words) }
    val controls = buildList {
        add(Control(tr("Use RP Launcher for texts"), spoken = tr("Use RP Launcher for texts. A helper sets this up once."), emphasized = true) { t.setUp() })
        if (current != null) {
            add(Control(tr("Open %s", current)) { t.openOtherApp() })
            add(Control(tr("Keep using %s", current), spoken = tr("Always open %s from the Texts tile", current)) {
                state.update { it.copy(textsElsewhere = true) }
                state.say(tr("The Texts tile will open %s. You can change this in Setup, Texting app.", current))
                t.openOtherApp()
            })
        } else {
            add(Control(tr("Phone settings"), spoken = tr("Open the phone's default apps setting")) {
                state.start(com.netgician.rplauncher.texts.TextRole.defaultAppsSettings(), tr("Could not open the setting"))
            })
        }
        add(Control(tr("Home"), icon = HOME_ICON) { state.go(Screen.Home) })
    }
    ReaderLayout(
        state = state, body = words, onBodyTap = { state.say(words) },
        header = tr("Texts"), headerSpoken = tr("Texts"), controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}
