package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.netgician.rplauncher.mms.Attachment
import com.netgician.rplauncher.mms.PartKind
import com.netgician.rplauncher.mms.PartKinds
import com.netgician.rplauncher.texts.MmsDownloads
import com.netgician.rplauncher.texts.MyNumbers
import com.netgician.rplauncher.texts.Names
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.TextMessage
import com.netgician.rplauncher.texts.TextPart
import com.netgician.rplauncher.texts.TextRole
import com.netgician.rplauncher.texts.TextSender
import com.netgician.rplauncher.texts.TextStore
import com.netgician.rplauncher.texts.TextsBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Something attached to a text being written: a photo, a voice message, a shared file. */
data class DraftFile(val path: String, val contentType: String) {
    val kind: PartKind get() = PartKinds.of(contentType, path)
}

/** A text being written. [threadId] is set when replying in a conversation. */
data class Draft(
    val to: List<String>,
    val text: String = "",
    val files: List<DraftFile> = emptyList(),
    val threadId: Long? = null,
)

/** A conversation's texts and people, kept in memory so it opens at once next time. */
class ThreadData(val messages: List<TextMessage>, val people: List<String>)

/** What a "Check texts" step is waiting to hear back about. */
enum class CheckStep(private val en: String) {
    TEXT("test text"), PICTURE("test picture text"), GROUP("test group text");

    val label: String get() = tr(en)
}

/** The texting part of the app: what's being written, played, and checked. */
class TextsState(private val app: AppState, private val activity: Activity, private val scope: CoroutineScope) {
    val names = Names(activity)

    var draft by mutableStateOf<Draft?>(null)

    /** The text whose attachments are listed on the Attachments screen. */
    var attachmentsOf by mutableStateOf<TextMessage?>(null)
    var unread by mutableIntStateOf(0)
        private set

    /** Set while picking another person to add to the text being written. */
    var addingPerson = false

    /** Asked once per start-up, so the question doesn't keep coming back. */
    var askedContacts = false

    /** Lines shown on the Check texts screen, newest last. */
    val checkLog = mutableStateListOf<String>()
    private var checking: CheckStep? = null

    private var player: MediaPlayer? = null
    var playingPart by mutableStateOf<Long?>(null)
        private set

    /** RP Launcher's own texts are shown: it's the texting app, or demo mode is on. */
    val isDefault: Boolean get() = Demo.on || TextRole.isDefault(activity)

    /** Demo mode was turned on or off: swap the real texts for the made-up ones, or back. */
    fun demoChanged() {
        synchronized(threads) { threads.clear() }
        conversations = null
        scope.launch { refreshCache() }
        refreshUnread()
    }

    // ---------------------------------------------------------------- loading, kept warm

    /** The conversation list, loaded in the background so Texts opens at once. Null until first loaded. */
    var conversations by mutableStateOf<List<com.netgician.rplauncher.texts.Conversation>?>(null)
        private set

    private val threads = object : LinkedHashMap<Long, ThreadData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThreadData>?) = size > 12
    }

    fun cachedThread(threadId: Long): ThreadData? = synchronized(threads) { threads[threadId] }

    /** Loads a conversation (the newest [limit] texts) and keeps it for next time. Call off the main thread. */
    fun loadThread(threadId: Long, limit: Int = 60, markRead: Boolean = true): ThreadData {
        if (Demo.on) return Demo.thread(threadId).also { d -> synchronized(threads) { threads[threadId] = d } }
        val store = TextStore(activity)
        val data = runCatching {
            val messages = store.messages(threadId, { id -> com.netgician.rplauncher.texts.SendRecords.download(activity, id) }, limit)
            if (markRead && messages.any { !it.read }) store.markRead(threadId)
            val people = conversations?.firstOrNull { it.threadId == threadId }?.addresses ?: store.recipients(threadId)
            ThreadData(messages, people)
        }.getOrElse { ThreadData(emptyList(), emptyList()) }
        synchronized(threads) { threads[threadId] = data }
        return data
    }

    /** Reloads the list, then the newest few conversations, so they're ready before they're opened. */
    suspend fun refreshCache() {
        if (Demo.on) {
            conversations = Demo.conversations
            return
        }
        if (!isDefault) return
        val list = withContext(Dispatchers.IO) {
            runCatching { TextStore(activity).conversations(names::of) }.getOrNull()
        } ?: return
        conversations = list
        withContext(Dispatchers.IO) {
            list.take(4).forEach { c -> if (TextsBus.openThread != c.threadId) loadThread(c.threadId, markRead = false) }
        }
    }

    init {
        // Whenever texts change, refresh in the background (after a short pause, so a burst of changes loads once).
        scope.launch {
            TextsBus.version.collectLatest {
                kotlinx.coroutines.delay(300)
                refreshCache()
            }
        }
    }

    fun refreshUnread() {
        if (Demo.on) { unread = Demo.UNREAD; return }
        if (!isDefault) { unread = 0; return }
        scope.launch {
            unread = withContext(Dispatchers.IO) { runCatching { TextStore(activity).unreadCount() }.getOrDefault(0) }
        }
    }

    /** The Texts tile: RP Launcher's own texts when it's the texting app, otherwise the phone's. */
    fun openTile() {
        when {
            isDefault -> app.go(Screen.Texts)
            app.config.textsElsewhere -> openOtherApp()
            else -> app.go(Screen.TextsNotSet)
        }
    }

    /** The phone's own texting app (or the one it last used). */
    fun openOtherApp() {
        val pkg = android.provider.Telephony.Sms.getDefaultSmsPackage(activity)
        val launch = pkg?.let { activity.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        app.start(launch, tr("No texting app found"))
    }

    /** Going on to make RP Launcher the texting app: a helper job, behind the PIN if there is one. */
    fun setUp() {
        app.go(if (app.config.pinHash != null) Screen.Pin(PinPurpose.Unlock, next = Screen.TextsSwitch) else Screen.TextsSwitch)
    }

    fun openThread(threadId: Long) {
        if (isDefault) app.go(Screen.Conversation(threadId))
    }

    /** Opened from another app ("text this person", or a shared photo). */
    fun compose(to: List<String>, body: String?, files: List<DraftFile>) {
        if (!isDefault) return
        draft = Draft(to = to, text = body.orEmpty(), files = files)
        if (to.isEmpty()) {
            addingPerson = true
            app.go(Screen.NewText)
            app.say(tr("New text. Pick who to send it to."))
        } else {
            app.go(Screen.Compose)
            app.say(tr("Text to %s.", People.join(to.map(names::of))))
        }
    }

    fun startReply(threadId: Long) {
        scope.launch {
            val people = if (Demo.on) Demo.thread(threadId).people
            else withContext(Dispatchers.IO) { runCatching { TextStore(activity).recipients(threadId) }.getOrDefault(emptyList()) }
            if (people.isEmpty()) {
                app.say(tr("Couldn't find who is in this conversation."))
                return@launch
            }
            val keep = draft?.takeIf { it.threadId == threadId }
            draft = keep ?: Draft(to = people, threadId = threadId)
            app.go(Screen.Compose)
            app.say(tr("Reply to %s.", People.join(people.map(names::of))))
        }
    }

    fun startNew() {
        draft = Draft(to = emptyList())
        addingPerson = true
        app.go(Screen.NewText)
    }

    /** A person was picked or typed in. */
    fun addPerson(number: String) {
        val d = draft ?: Draft(to = emptyList())
        draft = d.copy(to = (d.to + number).distinct(), threadId = null)
        addingPerson = false
        app.go(Screen.Compose)
        val who = names.of(number)
        app.say(if (d.to.isEmpty()) tr("Text to %s.", who) else tr("%s added. %s.", who, People.join(draft!!.to.map(names::of))))
    }

    fun cancelDraft() {
        draft?.files?.forEach { runCatching { File(it.path).delete() } }
        val back = draft?.threadId
        draft = null
        app.say(tr("Cancelled"))
        app.go(if (back != null) Screen.Conversation(back) else Screen.Texts)
    }

    fun send() {
        val d = draft ?: return
        if (d.text.isBlank() && d.files.isEmpty()) {
            app.say(tr("Nothing to send yet. Double tap Speak to say your message, or Quick replies."))
            return
        }
        if (d.to.isEmpty()) {
            app.say(tr("Pick who to send it to first."))
            return
        }
        if (Demo.on) {
            d.files.forEach { runCatching { File(it.path).delete() } }
            draft = null
            app.say(tr("Demo mode, so nothing was sent."))
            app.go(d.threadId?.let { Screen.Conversation(it) } ?: Screen.Texts)
            return
        }
        app.say(tr("Sending to %s.", People.join(d.to.map(names::of))))
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                val attachments = d.files.mapNotNull { f ->
                    runCatching { Attachment(f.contentType, File(f.path).name, File(f.path).readBytes()) }.getOrNull()
                }
                runCatching { TextSender.send(activity, d.to, d.text, attachments) }
                    .getOrElse { TextSender.Started(-1, false, tr("Something went wrong on the phone.")) }
            }
            if (!started.ok) {
                app.say(tr("Not sent. %s", started.problem ?: ""))
                return@launch
            }
            d.files.forEach { runCatching { File(it.path).delete() } }
            draft = null
            app.go(Screen.Conversation(started.threadId))
        }
    }

    fun retry(m: TextMessage) {
        if (Demo.on) { app.say(tr("Demo mode, so nothing was sent.")); return }
        app.say(tr("Trying again."))
        scope.launch(Dispatchers.IO) {
            if (m.box == com.netgician.rplauncher.texts.Box.WAITING) MmsDownloads.start(activity, m.id, attempt = 1)
            else TextSender.retry(activity, m)
        }
    }

    // ---------------------------------------------------------------- attachments

    fun play(part: TextPart) {
        if (playingPart == part.id) {
            stopPlaying()
            app.say(tr("Stopped"))
            return
        }
        stopPlaying()
        app.speaker.stop()
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(activity, Uri.parse(part.uri))
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener {
                stopPlaying()
                app.say(tr("End of clip"))
            }
            p.setOnErrorListener { _, _, _ ->
                stopPlaying()
                app.say(tr("This clip can't be played here. Double tap Open file to try another app."))
                true
            }
            p.prepareAsync()
            player = p
            playingPart = part.id
        }.onFailure {
            p.release()
            app.say(tr("This clip can't be played here."))
        }
    }

    fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingPart = null
    }

    /** Opens a video or any other file in the phone's own apps, from a private copy. */
    fun openElsewhere(part: TextPart) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = TextStore(activity).readPart(part.id) ?: return@runCatching null
                    val dir = File(activity.cacheDir, "open").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val name = com.netgician.rplauncher.mms.MmsPart(part.contentType, part.name, ByteArray(0)).safeFileName(part.id.toInt())
                    File(dir, name).apply { writeBytes(bytes) }
                }.getOrNull()
            }
            if (file == null) {
                app.say(tr("This file couldn't be read."))
                return@launch
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, part.contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                app.say(tr("No app on this phone can open this kind of file."))
            }
        }
    }

    // ---------------------------------------------------------------- events

    /** What's said when texts change. A failure is always said, wherever the person is. */
    fun onEvent(e: TextsBus.Event) {
        refreshUnread()
        // Never talk over another app or a phone call; notifications cover that.
        if (!TextsBus.foreground) return
        val screen = app.screen
        val inThread = (screen as? Screen.Conversation)?.threadId ?: (screen as? Screen.Message)?.threadId
        val onTexts = screen.isTextsScreen
        when (e) {
            is TextsBus.Event.Received -> {
                val mine = MyNumbers.get(activity).firstOrNull()
                val step = checking
                if (step != null && mine != null && e.who == names.of(mine)) {
                    checkLog += tr("%s received. It works.", step.label.replaceFirstChar { it.uppercase() })
                    app.speaker.speak(tr("%s received. It works.", step.label.replaceFirstChar { it.uppercase() }), flush = false)
                    checking = null
                    return
                }
                // In that conversation, the new text appears and is read out by the screen itself.
                if (screen is Screen.Conversation && inThread == e.threadId) return
                if (screen != Screen.See && screen != Screen.ReadText) app.speaker.speak(tr("New text from %s.", e.who), flush = false)
            }
            is TextsBus.Event.Sent -> {
                if (screen is Screen.Sos) return // the SOS screen says each result itself
                checking?.let { step ->
                    checkLog += if (e.ok) tr("%s sent. Waiting for it to arrive…", step.label.replaceFirstChar { it.uppercase() })
                    else tr("%s not sent. %s", step.label.replaceFirstChar { it.uppercase() }, e.words)
                    if (!e.ok) checking = null
                }
                when {
                    e.ok && (onTexts || screen.isHelperScreen) -> app.speaker.speak(tr("Sent."), flush = false)
                    !e.ok && inThread == e.threadId -> app.speaker.speak(tr("Not sent. %s Double tap Try again.", e.words), flush = false)
                    !e.ok -> app.speaker.speak(tr("A text was not sent. %s Open Texts to try again.", e.words), flush = false)
                }
            }
            is TextsBus.Event.DownloadFailed -> {
                checking?.let { step -> checkLog += tr("%s didn't download. %s", step.label.replaceFirstChar { it.uppercase() }, e.words); checking = null }
                app.speaker.speak(tr("A picture text from %s didn't download. %s", e.who, e.words), flush = false)
            }
        }
    }

    // ---------------------------------------------------------------- check texts

    /** Sends a test to this phone's own number (and, for a group, one more person) and reports each step. */
    fun check(step: CheckStep, other: String? = null) {
        val mine = MyNumbers.get(activity).firstOrNull()
        if (mine == null) {
            app.say(tr("First, type this phone's own number."))
            return
        }
        checking = step
        checkLog += tr("Sending a %s…", step.label)
        app.say(tr("Sending a %s.", step.label))
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    when (step) {
                        CheckStep.TEXT -> TextSender.send(activity, listOf(mine), tr("RP Launcher test text. You can delete this."))
                        CheckStep.PICTURE -> TextSender.send(
                            activity, listOf(mine), tr("RP Launcher test picture text."),
                            listOf(Attachment("image/png", "test.png", TestPicture.png())),
                        )
                        CheckStep.GROUP -> TextSender.send(
                            activity, listOfNotNull(mine, other), tr("Testing group texts from RP Launcher. You can ignore this."),
                        )
                    }
                }.getOrElse { TextSender.Started(-1, false, tr("Something went wrong on the phone.")) }
            }
            if (!started.ok) {
                checkLog += tr("Not sent. %s", started.problem)
                app.say(tr("Not sent. %s", started.problem))
                checking = null
            }
        }
    }
}

/** Screens that belong to Texts, where "Sent" is said aloud. */
val Screen.isTextsScreen: Boolean
    get() = this is Screen.Texts || this is Screen.Conversation || this is Screen.Message || this is Screen.Compose || this is Screen.QuickReply ||
        this is Screen.ComposeMore || this is Screen.Attachments ||
        this is Screen.NewText || this is Screen.NumberPad || this is Screen.Picture || this is Screen.Card ||
        this is Screen.Invite || this is Screen.Record || this is Screen.AddPhoto

/** A tiny picture for the picture text check: a bright square. */
private object TestPicture {
    fun png(): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.rgb(255, 196, 0))
        val out = java.io.ByteArrayOutputStream()
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }
}
