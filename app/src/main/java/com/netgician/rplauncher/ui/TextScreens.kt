package com.netgician.rplauncher.ui

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
private fun ChoiceList(state: AppState, title: String, spokenTitle: String = title, choices: List<Control>) {
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
    val version by TextsBus.version.collectAsState()
    var list by remember { mutableStateOf<List<Conversation>?>(null) }
    LaunchedEffect(version) {
        list = withContext(Dispatchers.IO) {
            runCatching { TextStore(context).conversations(t.names::of) }.getOrDefault(emptyList())
        }
    }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            t.names.forget()
            TextsBus.changed()
        }
    }
    LaunchedEffect(Unit) {
        if (!t.names.canRead && !t.askedContacts) {
            t.askedContacts = true
            state.say("To show names instead of numbers, allow contacts.")
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
                    loaded.isEmpty() -> "Texts. No conversations yet. Double tap New text to start one."
                    n == 0 -> "Texts"
                    n == 1 -> "Texts, 1 new"
                    else -> "Texts, $n new"
                },
                flush = false,
            )
        }
    }
    val choices = buildList {
        add(Control("Home", icon = HOME_ICON) { state.go(Screen.Home) })
        add(Control("New text", spoken = "Start a new text", icon = TileIcon.Vector(Icons.Filled.Add)) { t.startNew() })
        loaded.orEmpty().forEach { c ->
            val label = if (c.unread > 0) "${c.title} (${c.unread})" else c.title
            add(Control(label, spoken = TextSpeech.conversation(c), emphasized = c.unread > 0 || c.hasProblem) {
                state.go(Screen.Conversation(c.threadId))
            })
        }
    }
    ChoiceList(state, if (loaded == null) "Texts…" else "Texts", choices = choices)
}

private class Loaded(val messages: List<TextMessage>, val people: List<String>)

/** One conversation, one text at a time: big words in the top half, big controls below. */
@Composable
fun ConversationScreen(state: AppState, threadId: Long) {
    val context = LocalContext.current
    val t = state.texts
    val version by TextsBus.version.collectAsState()
    var loaded by remember(threadId) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(threadId, version) {
        loaded = withContext(Dispatchers.IO) {
            runCatching {
                val store = TextStore(context)
                val messages = store.messages(threadId, { id -> SendRecords.download(context, id) })
                if (messages.any { !it.read }) store.markRead(threadId)
                Loaded(messages, store.recipients(threadId))
            }.getOrElse { Loaded(emptyList(), emptyList()) }
        }
        t.refreshUnread()
    }
    DisposableEffect(threadId) {
        TextsBus.openThread = threadId
        TextNotifications.cancel(context, threadId)
        onDispose { if (TextsBus.openThread == threadId) TextsBus.openThread = null }
    }

    val data = loaded
    var index by remember(threadId) { mutableIntStateOf(-1) }
    LaunchedEffect(data) {
        val msgs = data?.messages ?: return@LaunchedEffect
        if (msgs.isNotEmpty() && index !in msgs.indices) {
            // Start at the first new text, or the newest one.
            index = msgs.indexOfFirst { !it.read && !it.outgoing }.takeIf { it >= 0 } ?: msgs.lastIndex
        }
    }
    val title = data?.people?.let { p -> People.join(p.map(t.names::of)) } ?: "Texts"
    val msgs = data?.messages.orEmpty()

    if (data == null || msgs.isEmpty()) {
        val words = if (data == null) "Loading…" else "No texts here yet."
        ReaderLayout(
            state = state, body = words, onBodyTap = { state.say(words) }, header = title, headerSpoken = title,
            controls = listOf(
                Control("Reply", icon = SEND_ICON) { t.startReply(threadId) },
                Control("Texts") { state.go(Screen.Texts) },
                Control("Home", icon = HOME_ICON) { state.go(Screen.Home) },
            ),
        ) { c, m, pos -> ControlTile(state, c, m, pos) }
        return
    }

    val i = if (index in msgs.indices) index else msgs.lastIndex
    val m = msgs[i]
    val group = data.people.size > 1
    val fromName = m.from?.takeIf { it.isNotBlank() }?.let(t.names::of)
    val whenText = TextSpeech.whenSaid(m.date, System.currentTimeMillis()) { DateFormat.getTimeFormat(context).format(Date(it)) }
    val spoken = TextSpeech.spoken(m, fromName, group, whenText)

    fun speakMessage() {
        state.speaker.speak("Text ${i + 1} of ${msgs.size}. ${spoken.head}")
        if (spoken.body.isNotBlank()) state.speaker.speak(spoken.body, Language.guess(spoken.body), flush = false)
        if (spoken.tail.isNotBlank()) state.speaker.speak(spoken.tail, flush = false)
    }
    LaunchedEffect(m.key) { speakMessage() }

    val shown = buildString {
        append(if (m.outgoing) "You" else fromName ?: "Unknown")
        append(": ")
        append(
            when {
                m.box == Box.WAITING -> "Picture text " + when (m.download) {
                    DownloadState.FAILED -> "didn't download"
                    DownloadState.RETRYING -> "will try again soon"
                    DownloadState.NOT_STARTED -> "waiting"
                    DownloadState.DOWNLOADING -> "downloading…"
                }
                m.body.isNotBlank() -> m.body.trim()
                else -> TextSpeech.attachments(m.parts).ifBlank { "No words" }
            },
        )
        when {
            m.box == Box.FAILED -> append("\nNOT SENT")
            m.box == Box.SENDING -> append("\nSending…")
            m.outgoing && m.delivered -> append("\nDelivered")
        }
    }

    val firstImage = m.parts.firstOrNull { it.kind == PartKind.IMAGE }
    val thumb by produceState<Bitmap?>(null, firstImage?.id) {
        value = firstImage?.let { p -> withContext(Dispatchers.IO) { Pictures.load(context, p.id, 900) } }
    }

    // At most six controls, so they fit on one page: Older, Newer, Reply, the one thing this
    // text needs (Try again, See picture, Play...), Call or Read again, and back to all texts.
    // Tapping the words reads them again.
    val extras = attachmentControls(state, threadId, m)
    val action: Control? = when {
        m.box == Box.FAILED -> Control("Try again", spoken = "Try sending again", emphasized = true) { t.retry(m) }
        m.box == Box.WAITING -> when (m.download) {
            DownloadState.DOWNLOADING -> Control("Downloading", spoken = "Still downloading") {
                state.say("Still downloading. You'll hear when it arrives.")
            }
            DownloadState.NOT_STARTED -> Control("Download", spoken = "Download the picture text", emphasized = true) { t.retry(m) }
            else -> Control("Try again", spoken = "Try downloading again", emphasized = true) { t.retry(m) }
        }
        extras.size == 1 -> extras[0]
        extras.size > 1 -> Control("Attachments (${extras.size})", spoken = "See all ${extras.size} attachments", emphasized = true) {
            t.attachmentsOf = m
            state.go(Screen.Attachments(threadId))
        }
        else -> null
    }
    val readAgain = Control("Read again", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { speakMessage() }
    val callee = if (!m.outgoing && !m.from.isNullOrBlank()) m.from else data.people.singleOrNull()
    val call = callee?.let { number ->
        val name = t.names.of(number)
        Control("Call", spoken = "Call $name", icon = TileIcon.Vector(Icons.Filled.Call)) {
            state.go(Screen.ConfirmCall(TileSpec.Contact(name, number)))
        }
    }
    val controls = listOfNotNull(
        Control("Older", spoken = "Older text") { if (i > 0) index = i - 1 else state.say("This is the first text") },
        Control("Newer", spoken = "Newer text") { if (i < msgs.lastIndex) index = i + 1 else state.say("This is the newest text") },
        Control("Reply", icon = SEND_ICON) { t.startReply(threadId) },
        action ?: readAgain,
        call ?: (if (action != null) readAgain else Control("Home", icon = HOME_ICON) { state.go(Screen.Home) }),
        Control("All texts", spoken = "Back to all texts") { state.go(Screen.Texts) },
    )

    val lv = LocalLv.current
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = { speakMessage() },
        header = "${i + 1} of ${msgs.size} · $title",
        headerSpoken = "Text ${i + 1} of ${msgs.size}, with $title",
        controls = controls,
        bodyWeight = 0.45f,
        controlsHeader = false,
        bodyContent = {
            val b = thumb
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FitText(
                    text = shown,
                    color = lv.fg,
                    maxSp = 30f * lv.textScale,
                    minSp = 14f,
                    maxLines = if (b != null) 3 else 8,
                    align = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
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
            add(Control(if (images.size == 1) "See picture" else "See pictures", spoken = "See the picture bigger", emphasized = true) {
                state.go(Screen.Picture(threadId, images.map { it.id }, 0))
            })
        }
        m.parts.filter { it.kind == PartKind.AUDIO }.forEach { a ->
            val playing = t.playingPart == a.id
            add(Control(if (playing) "Stop" else "Play", spoken = if (playing) "Stop the clip" else "Play the clip",
                icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) { t.play(a) })
        }
        m.parts.filter { it.kind == PartKind.VIDEO }.forEach { v ->
            add(Control("Play video", spoken = "Play the video", icon = TileIcon.Vector(Icons.Filled.PlayArrow), emphasized = true) {
                t.openElsewhere(v)
            })
        }
        m.parts.filter { it.kind == PartKind.CONTACT }.forEach { c ->
            add(Control("Contact card", spoken = "Open the contact card", emphasized = true) { state.go(Screen.Card(threadId, c.id)) })
        }
        m.parts.filter { it.kind == PartKind.EVENT }.forEach { e ->
            add(Control("Invite", spoken = "Open the calendar invite", emphasized = true) { state.go(Screen.Invite(threadId, e.id)) })
        }
        m.parts.filter { it.kind == PartKind.OTHER }.forEach { o ->
            add(Control("Open file", spoken = "Open the file in another app") { t.openElsewhere(o) })
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
    LaunchedEffect(Unit) { state.say("Attachments. ${TextSpeech.attachments(m.parts)}") }
    ChoiceList(
        state, "Attachments",
        choices = listOf(Control("Back", spoken = "Back to the text") { state.go(Screen.Conversation(threadId)) }) +
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
            state.say("Your text: $text. Double tap Send to send it, or Speak to add more.")
        } else {
            state.say("Nothing heard. Double tap Speak to try again.")
        }
    }
    val who = People.join(d.to.map(t.names::of))
    val attached = TextSpeech.attachments(d.files.map { TextPart(0, it.kind, it.contentType, null, "") })
    val shown = d.text.ifBlank { "Nothing written yet." } + if (attached.isNotEmpty()) "\nWith: ${attached.removeSuffix(".").lowercase()}" else ""
    val readBack = {
        state.say(
            if (d.text.isBlank() && d.files.isEmpty()) "Nothing written yet. Double tap Speak to say your message."
            else "Text to $who. ${d.text} ${attached}".trim(),
        )
    }

    if (typing) {
        TypingBox(state, d.text) { typed ->
            t.draft = t.draft?.copy(text = typed)
            typing = false
            state.say(if (typed.isBlank()) "Nothing typed." else "Your text: $typed. Double tap Send to send it.")
        }
        return
    }

    val controls = buildList {
        add(Control("Speak", spoken = "Speak your message") {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
            state.speaker.stop()
            try {
                dictate.launch(i)
            } catch (e: ActivityNotFoundException) {
                state.say("Speech typing isn't available on this phone. Use Keyboard or Quick replies.")
            }
        })
        add(Control("Send", spoken = "Send to $who", icon = SEND_ICON, emphasized = true) { t.send() })
        add(Control("Quick replies", spoken = "Choose a quick reply") { state.go(Screen.QuickReply) })
        add(Control("Keyboard", spoken = "Type with the keyboard") { typing = true })
        add(Control("More", spoken = "More: add a photo, a voice message or a person") { state.go(Screen.ComposeMore) })
        add(Control("Cancel", spoken = "Cancel this text", icon = TileIcon.Vector(Icons.Filled.Close)) { t.cancelDraft() })
    }
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = readBack,
        header = "To $who",
        headerSpoken = "Text to $who",
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
    LaunchedEffect(Unit) { state.say("More") }
    val who = People.join(d.to.map(t.names::of))
    val attached = TextSpeech.attachments(d.files.map { TextPart(0, it.kind, it.contentType, null, "") })
    ChoiceList(
        state, "More",
        choices = buildList {
            add(Control("Back", spoken = "Back to the text") { state.go(Screen.Compose) })
            add(Control("Add photo", spoken = "Add a photo or video") { state.go(Screen.AddPhoto) })
            add(Control("Voice message", spoken = "Record a voice message") { state.go(Screen.Record) })
            add(Control("Add a person", spoken = "Add a person to this text") {
                t.addingPerson = true
                state.go(Screen.NewText)
            })
            add(Control("Read it back", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) {
                state.say(
                    if (d.text.isBlank() && d.files.isEmpty()) "Nothing written yet."
                    else "Text to $who. ${d.text} $attached".trim(),
                )
            })
            if (d.text.isNotBlank() || d.files.isNotEmpty()) {
                add(Control("Clear", spoken = "Clear what's written") {
                    t.draft = d.copy(text = "", files = emptyList())
                    state.say("Cleared")
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
        state.say("Type your message. Press Done on the keyboard when you're finished.")
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
            Tile("Done typing", Modifier.fillMaxWidth().height((64f * lv.textScale).dp), emphasized = true) { finish() }
        }
    }
}

@Composable
fun QuickReplyScreen(state: AppState) {
    val t = state.texts
    LaunchedEffect(Unit) { state.say("Quick replies") }
    val replies = QuickReplies.forLanguage(Locale.getDefault().language)
    val choices = listOf(Control("Back", spoken = "Back to the text") { state.go(Screen.Compose) }) +
        replies.map { r ->
            Control(r) {
                t.draft = t.draft?.copy(text = r)
                state.go(Screen.Compose)
                state.say("$r. Double tap Send to send it.")
            }
        }
    ChoiceList(state, "Quick replies", choices = choices)
}

@Composable
fun NewTextScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        val c = readPhoneContact(context, uri)
        if (c == null) state.say("Couldn't read that contact") else t.addPerson(c.number)
    }
    val first = t.draft?.to.isNullOrEmpty()
    LaunchedEffect(Unit) {
        state.say(if (first) "New text. Who is it to? Pick a contact, or type a number." else "Add a person. Pick a contact, or type a number.")
    }
    ChoiceList(
        state, if (first) "Who to?" else "Add a person",
        choices = listOf(
            Control("Back") { state.back() },
            Control("Pick a contact", icon = TileIcon.Vector(Icons.Filled.Person)) {
                runCatching {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }.onFailure { state.say("No contacts app found") }
            },
            Control("Type a number", spoken = "Type a phone number") { state.go(Screen.NumberPad(NumberPurpose.TEXT)) },
        ),
    )
}

/** A big number pad. Each digit is said aloud as it's pressed. */
@Composable
fun NumberPadScreen(state: AppState, purpose: NumberPurpose) {
    val context = LocalContext.current
    var number by remember(purpose) { mutableStateOf("") }
    LaunchedEffect(purpose) {
        state.say(if (purpose == NumberPurpose.OWN_NUMBER) "Type this phone's own number" else "Type the phone number")
    }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "done")
    InWindow(state.config.window) {
        PagedTiles(
            items = keys,
            page = 0,
            onPage = { _, _ -> },
            cols = 3,
            rows = 4,
            onSpeak = state::say,
            header = { m ->
                SpokenText(
                    number.ifEmpty { "Type the number" }, m, state::say,
                    spoken = if (number.isEmpty()) "Nothing typed yet" else People.spoken(number),
                )
            },
            minTarget = 0f,
        ) { key, m, _ ->
            when (key) {
                "delete" -> if (number.isEmpty()) {
                    Tile("Back", m) { state.back() }
                } else {
                    Tile("Delete", m, spoken = "Delete the last digit") {
                        number = number.dropLast(1)
                        state.say("Deleted")
                    }
                }
                "done" -> Tile("Done", m, emphasized = true) {
                    if (number.length < 3) {
                        state.say("Type the whole number first")
                    } else if (purpose == NumberPurpose.OWN_NUMBER) {
                        SendRecords.learnNumber(context, number)
                        state.say("Saved. This phone's number is ${People.spoken(number)}.")
                        state.go(Screen.TextsCheck)
                    } else {
                        state.texts.addPerson(number)
                    }
                }
                else -> Tile(key, m) {
                    if (number.length < 15) {
                        number += key
                        state.say(key)
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
        val bytes = Photos.fit(context, uri, budget) ?: return null to "That picture couldn't be read."
        val f = File(dir, "photo-${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        return DraftFile(f.absolutePath, "image/jpeg") to "Picture added."
    }
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return null to "That file couldn't be read."
    if (bytes.size > budget) {
        val what = if (type.startsWith("video/")) "video" else "file"
        return null to "That $what is too big to send as a picture text. Try a shorter one."
    }
    val ext = when {
        type == "video/mp4" -> "mp4"
        type.startsWith("video/3gpp") -> "3gp"
        type.startsWith("video/") -> "mp4"
        else -> "bin"
    }
    val f = File(dir, "file-${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
    return DraftFile(f.absolutePath, type) to (if (type.startsWith("video/")) "Video added." else "File added.")
}

@Composable
fun AddPhotoScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun attach(uri: Uri, typeHint: String?) {
        state.say("Getting it ready.")
        scope.launch {
            val used = t.draft?.files?.sumOf { File(it.path).length().toInt() } ?: 0
            val (file, words) = withContext(Dispatchers.IO) { prepareAttachment(context, uri, typeHint, used) }
            if (file != null) {
                t.draft = t.draft?.let { it.copy(files = it.files + file) }
                state.go(Screen.Compose)
                state.say("$words Double tap Send to send it.")
            } else {
                state.say(words)
            }
        }
    }

    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) attach(u, "image/jpeg") else state.say("No photo taken")
    }
    fun openCamera() {
        val f = File(context.cacheDir, "camera/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val u = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        cameraUri = u
        runCatching { take.launch(u) }.onFailure { state.say("No camera app found") }
    }
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else state.say("The camera is not allowed. You can allow it in the phone's settings.")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attach(uri, null) else state.say("Nothing chosen")
    }
    LaunchedEffect(Unit) { state.say("Add a photo. Take one now, or choose one you have.") }
    ChoiceList(
        state, "Add a photo",
        choices = listOf(
            Control("Back", spoken = "Back to the text") { state.go(Screen.Compose) },
            Control("Take a photo", spoken = "Take a photo with the camera") {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) openCamera() else cameraAllowed.launch(Manifest.permission.CAMERA)
            },
            Control("Choose a photo", spoken = "Choose a photo or video you already have") {
                runCatching {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }.onFailure { state.say("No photo picker found") }
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
        state.say(said ?: if (file != null) "Recorded $seconds seconds. Double tap Send it, or Play it back to listen." else "Nothing was recorded. Try again.")
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
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording("Two minutes reached. Recording stopped. Double tap Send it, or Play it back.")
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
            state.say("The microphone couldn't start.")
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else state.say("The microphone is not allowed. You can allow it in the phone's settings.")
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
    LaunchedEffect(Unit) { state.say("Voice message. Double tap Start recording, then speak. A buzz means it's recording.") }

    val f = file
    val controls = when {
        recording -> listOf(Control("Stop recording", spoken = "Stop recording", emphasized = true) { stopRecording() })
        f == null -> listOf(
            Control("Start recording", spoken = "Start recording", emphasized = true) {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (ok) startRecording() else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            Control("Back", spoken = "Back to the text") { state.go(Screen.Compose) },
        )
        else -> listOf(
            Control("Send it", spoken = "Send the voice message", icon = SEND_ICON, emphasized = true) {
                t.draft = t.draft?.let { it.copy(files = it.files + DraftFile(f.absolutePath, "audio/mp4")) }
                file = null
                t.send()
            },
            Control("Play it back", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) {
                player?.release()
                player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(f.absolutePath)
                        setOnCompletionListener { state.say("End of recording") }
                        prepare()
                        start()
                    }
                }.getOrNull()
            },
            Control("Record again") {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (ok) startRecording() else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            Control("Cancel", spoken = "Throw this recording away", icon = TileIcon.Vector(Icons.Filled.Close)) {
                f.delete()
                file = null
                state.go(Screen.Compose)
                state.say("Recording thrown away")
            },
        )
    }
    val shown = when {
        recording -> "Recording… $seconds s"
        f != null -> "Recorded $seconds seconds"
        else -> "Ready to record"
    }
    ReaderLayout(
        state = state, body = shown, onBodyTap = { if (!recording) state.say(shown) },
        header = "Voice message", headerSpoken = "Voice message", controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}

// ==================================================================== attachments

/** Loads a picture from a picture text, no bigger than [maxSide] on its long side. */
internal object Pictures {
    fun load(context: Context, partId: Long, maxSide: Int): Bitmap? = runCatching {
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
        val which = if (count > 1) "Picture ${screen.index + 1} of $count." else "Picture."
        state.say("$which Closer and Wider zoom. Read text reads any words in it.")
    }
    val controls = buildList {
        add(Control("Closer", spoken = "Zoom closer") {
            scale = CameraMath.closer(scale, CameraMath.FROZEN_MAX_ZOOM)
            state.say(CameraMath.describeZoom(scale))
        })
        add(Control("Wider", spoken = "Zoom wider") {
            scale = CameraMath.wider(scale, 1f)
            if (scale <= 1f) offset = Offset.Zero
            state.say(CameraMath.describeZoom(scale))
        })
        add(Control(if (reading) "Reading…" else "Read text", spoken = "Read any words in the picture", emphasized = true) {
            val b = bitmap
            if (b == null || reading) return@Control
            reading = true
            state.say("Reading")
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCatching { state.ocr().read(b.toRgbImage()) }.getOrNull() }
                reading = false
                if (result == null) state.say("The words couldn't be read.") else state.showRead(result, backTo = screen)
            }
        })
        add(Control(filter.label, spoken = "Colours: ${filter.label}") {
            filter = CameraMath.Filter.entries.after(filter)
            state.say(filter.label)
        })
        if (count > 1) {
            add(Control("Next picture") {
                state.go(screen.copy(index = (screen.index + 1) % count))
            })
        }
        add(Control("Back", spoken = "Back to the text") { state.go(Screen.Conversation(screen.threadId)) })
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
                    .semantics { contentDescription = "Picture, ${CameraMath.describeZoom(scale)}" },
                contentAlignment = Alignment.Center,
            ) {
                val b = bitmap
                if (b == null) {
                    FitText("Loading…", lv.fg, maxSp = 24f * lv.textScale)
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
                        val status = (if (count > 1) "${screen.index + 1} of $count · " else "") + CameraMath.shortZoom(scale)
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
    val back = Control("Back", spoken = "Back to the text") { state.go(Screen.Conversation(screen.threadId)) }
    if (list == null || list.isEmpty()) {
        val words = if (list == null) "Loading…" else "This contact card is empty or damaged."
        LaunchedEffect(list) { if (list != null) state.say(words) }
        ReaderLayout(state, words, { state.say(words) }, "Contact card", "Contact card", listOf(back)) { c, m, pos -> ControlTile(state, c, m, pos) }
        return
    }
    val card = list[index.coerceIn(0, list.lastIndex)]
    LaunchedEffect(card) { state.say(card.speech) }
    val shown = buildString {
        append(card.name.ifBlank { "No name" })
        card.organization?.let { append("\n").append(it) }
        card.phones.forEach { append("\n${it.kind}: ${it.number}") }
        card.emails.forEach { append("\n").append(it) }
        card.address?.let { append("\n").append(it) }
    }
    val controls = buildList {
        card.phones.forEachIndexed { n, p ->
            val label = if (card.phones.size == 1) "Call" else "Call ${p.kind.lowercase()}"
            add(Control(label, spoken = "Call ${card.name} ${p.kind.lowercase()}", icon = TileIcon.Vector(Icons.Filled.Call), emphasized = n == 0) {
                state.go(Screen.ConfirmCall(TileSpec.Contact(card.name.ifBlank { p.number }, p.number)))
            })
        }
        card.phones.firstOrNull()?.let { p ->
            add(Control("Text", spoken = "Send ${card.name} a text", icon = SEND_ICON) {
                state.texts.draft = Draft(to = listOf(p.number))
                state.go(Screen.Compose)
                state.say("Text to ${card.name}.")
            })
        }
        add(Control("Save to contacts", spoken = "Save ${card.name} to contacts") {
            val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, card.name)
            card.phones.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it.number) }
            card.phones.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it.number) }
            card.emails.firstOrNull()?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            card.organization?.let { i.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            card.address?.let { i.putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
            state.start(i, "No contacts app found")
        })
        add(Control("Read again", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(card.speech) })
        if (list.size > 1) add(Control("Next card") { index = (index + 1) % list.size })
        add(back)
    }
    ReaderLayout(
        state, shown, { state.say(card.speech) },
        header = if (list.size > 1) "Card ${index + 1} of ${list.size}" else "Contact card",
        headerSpoken = "Contact card for ${card.name}",
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
    val back = Control("Back", spoken = "Back to the text") { state.go(Screen.Conversation(screen.threadId)) }
    if (list == null || list.isEmpty()) {
        val words = if (list == null) "Loading…" else "This invite is empty or damaged."
        LaunchedEffect(list) { if (list != null) state.say(words) }
        ReaderLayout(state, words, { state.say(words) }, "Invite", "Calendar invite", listOf(back)) { c, m, pos -> ControlTile(state, c, m, pos) }
        return
    }
    val e = list[index.coerceIn(0, list.lastIndex)]
    val speech = e.speech()
    LaunchedEffect(e) { state.say(speech) }
    val shown = buildString {
        append(e.title.ifBlank { "No title" })
        e.startMillis?.let { append("\n").append(e.whenText(it)) }
        e.location?.let { append("\n").append(it) }
        e.description?.let { append("\n").append(it) }
    }
    val controls = buildList {
        add(Control("Add to calendar", spoken = "Add ${e.title} to the calendar", emphasized = true) {
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, e.title)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
            e.startMillis?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            e.endMillis?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            e.location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            e.description?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            state.start(i, "No calendar app found")
        })
        add(Control("Read again", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(speech) })
        if (list.size > 1) add(Control("Next event") { index = (index + 1) % list.size })
        add(back)
    }
    ReaderLayout(
        state, shown, { state.say(speech) },
        header = if (list.size > 1) "Invite ${index + 1} of ${list.size}" else "Invite",
        headerSpoken = "Calendar invite",
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
        "RP Launcher isn't the texting app yet, and no texting app is set on this phone right now. " +
            "Choose Use RP Launcher for texts to set it up."
    } else {
        "RP Launcher isn't the texting app yet. Right now, texts go to $current. " +
            "Choose Use RP Launcher for texts to have them here instead, or keep using $current."
    }
    LaunchedEffect(Unit) { state.say(words) }
    val controls = buildList {
        add(Control("Use RP Launcher for texts", spoken = "Use RP Launcher for texts. A helper sets this up once.", emphasized = true) { t.setUp() })
        if (current != null) {
            add(Control("Open $current") { t.openOtherApp() })
            add(Control("Keep using $current", spoken = "Always open $current from the Texts tile") {
                state.update { it.copy(textsElsewhere = true) }
                state.say("The Texts tile will open $current. You can change this in Setup, Texting app.")
                t.openOtherApp()
            })
        } else {
            add(Control("Phone settings", spoken = "Open the phone's default apps setting") {
                state.start(com.netgician.rplauncher.texts.TextRole.defaultAppsSettings(), "Could not open the setting")
            })
        }
        add(Control("Home", icon = HOME_ICON) { state.go(Screen.Home) })
    }
    ReaderLayout(
        state = state, body = words, onBodyTap = { state.say(words) },
        header = "Texts", headerSpoken = "Texts", controls = controls,
    ) { c, m, pos -> ControlTile(state, c, m, pos) }
}
