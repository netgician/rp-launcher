package com.netgician.rplauncher.ui

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

/** What a "Check texts" step is waiting to hear back about. */
enum class CheckStep(val label: String) { TEXT("test text"), PICTURE("test picture text"), GROUP("test group text") }

/** The texting part of the app: what's being written, played, and checked. */
class TextsState(private val app: AppState, private val activity: Activity, private val scope: CoroutineScope) {
    val names = Names(activity)

    var draft by mutableStateOf<Draft?>(null)
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

    val isDefault: Boolean get() = TextRole.isDefault(activity)

    fun refreshUnread() {
        if (!isDefault) { unread = 0; return }
        scope.launch {
            unread = withContext(Dispatchers.IO) { runCatching { TextStore(activity).unreadCount() }.getOrDefault(0) }
        }
    }

    /** The Texts tile: RP Launcher's own texts when it's the texting app, otherwise the phone's. */
    fun openTile() {
        if (isDefault) {
            app.go(Screen.Texts)
        } else {
            app.start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
        }
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
            app.say("New text. Pick who to send it to.")
        } else {
            app.go(Screen.Compose)
            app.say("Text to ${People.join(to.map(names::of))}.")
        }
    }

    fun startReply(threadId: Long) {
        scope.launch {
            val people = withContext(Dispatchers.IO) { runCatching { TextStore(activity).recipients(threadId) }.getOrDefault(emptyList()) }
            if (people.isEmpty()) {
                app.say("Couldn't find who is in this conversation.")
                return@launch
            }
            val keep = draft?.takeIf { it.threadId == threadId }
            draft = keep ?: Draft(to = people, threadId = threadId)
            app.go(Screen.Compose)
            app.say("Reply to ${People.join(people.map(names::of))}.")
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
        app.say(if (d.to.isEmpty()) "Text to $who." else "$who added. ${People.join(draft!!.to.map(names::of))}.")
    }

    fun cancelDraft() {
        draft?.files?.forEach { runCatching { File(it.path).delete() } }
        val back = draft?.threadId
        draft = null
        app.say("Cancelled")
        app.go(if (back != null) Screen.Conversation(back) else Screen.Texts)
    }

    fun send() {
        val d = draft ?: return
        if (d.text.isBlank() && d.files.isEmpty()) {
            app.say("Nothing to send yet. Double tap Speak to say your message, or Quick replies.")
            return
        }
        if (d.to.isEmpty()) {
            app.say("Pick who to send it to first.")
            return
        }
        app.say("Sending to ${People.join(d.to.map(names::of))}.")
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                val attachments = d.files.mapNotNull { f ->
                    runCatching { Attachment(f.contentType, File(f.path).name, File(f.path).readBytes()) }.getOrNull()
                }
                runCatching { TextSender.send(activity, d.to, d.text, attachments) }
                    .getOrElse { TextSender.Started(-1, false, "Something went wrong on the phone.") }
            }
            if (!started.ok) {
                app.say("Not sent. ${started.problem ?: ""}")
                return@launch
            }
            d.files.forEach { runCatching { File(it.path).delete() } }
            draft = null
            app.go(Screen.Conversation(started.threadId))
        }
    }

    fun retry(m: TextMessage) {
        app.say("Trying again.")
        scope.launch(Dispatchers.IO) {
            if (m.box == com.netgician.rplauncher.texts.Box.WAITING) MmsDownloads.start(activity, m.id, attempt = 1)
            else TextSender.retry(activity, m)
        }
    }

    // ---------------------------------------------------------------- attachments

    fun play(part: TextPart) {
        if (playingPart == part.id) {
            stopPlaying()
            app.say("Stopped")
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
                app.say("End of clip")
            }
            p.setOnErrorListener { _, _, _ ->
                stopPlaying()
                app.say("This clip can't be played here. Double tap Open file to try another app.")
                true
            }
            p.prepareAsync()
            player = p
            playingPart = part.id
        }.onFailure {
            p.release()
            app.say("This clip can't be played here.")
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
                app.say("This file couldn't be read.")
                return@launch
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, part.contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                app.say("No app on this phone can open this kind of file.")
            }
        }
    }

    // ---------------------------------------------------------------- events

    /** What's said when texts change. A failure is always said, wherever the person is. */
    fun onEvent(e: TextsBus.Event) {
        refreshUnread()
        val screen = app.screen
        val inThread = (screen as? Screen.Conversation)?.threadId
        val onTexts = screen.isTextsScreen
        when (e) {
            is TextsBus.Event.Received -> {
                val mine = MyNumbers.get(activity).firstOrNull()
                val step = checking
                if (step != null && mine != null && e.who == names.of(mine)) {
                    checkLog += "${step.label.replaceFirstChar { it.uppercase() }} received. It works."
                    app.speaker.speak("${step.label.replaceFirstChar { it.uppercase() }} received. It works.", flush = false)
                    checking = null
                    return
                }
                val words = if (inThread == e.threadId) "New text from ${e.who}. Double tap Newer to read it."
                else "New text from ${e.who}."
                if (screen != Screen.See && screen != Screen.ReadText) app.speaker.speak(words, flush = false)
            }
            is TextsBus.Event.Sent -> {
                checking?.let { step ->
                    checkLog += if (e.ok) "${step.label.replaceFirstChar { it.uppercase() }} sent. Waiting for it to arrive…"
                    else "${step.label.replaceFirstChar { it.uppercase() }} not sent. ${e.words}"
                    if (!e.ok) checking = null
                }
                when {
                    e.ok && (onTexts || screen.isHelperScreen) -> app.speaker.speak("Sent.", flush = false)
                    !e.ok && inThread == e.threadId -> app.speaker.speak("Not sent. ${e.words} Double tap Try again.", flush = false)
                    !e.ok -> app.speaker.speak("A text was not sent. ${e.words} Open Texts to try again.", flush = false)
                }
            }
            is TextsBus.Event.DownloadFailed -> {
                checking?.let { step -> checkLog += "${step.label.replaceFirstChar { it.uppercase() }} didn't download. ${e.words}"; checking = null }
                app.speaker.speak("A picture text from ${e.who} didn't download. ${e.words}", flush = false)
            }
        }
    }

    // ---------------------------------------------------------------- check texts

    /** Sends a test to this phone's own number (and, for a group, one more person) and reports each step. */
    fun check(step: CheckStep, other: String? = null) {
        val mine = MyNumbers.get(activity).firstOrNull()
        if (mine == null) {
            app.say("First, type this phone's own number.")
            return
        }
        checking = step
        checkLog += "Sending a ${step.label}…"
        app.say("Sending a ${step.label}.")
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    when (step) {
                        CheckStep.TEXT -> TextSender.send(activity, listOf(mine), "RP Launcher test text. You can delete this.")
                        CheckStep.PICTURE -> TextSender.send(
                            activity, listOf(mine), "RP Launcher test picture text.",
                            listOf(Attachment("image/png", "test.png", TestPicture.png())),
                        )
                        CheckStep.GROUP -> TextSender.send(
                            activity, listOfNotNull(mine, other), "Testing group texts from RP Launcher. You can ignore this.",
                        )
                    }
                }.getOrElse { TextSender.Started(-1, false, "Something went wrong on the phone.") }
            }
            if (!started.ok) {
                checkLog += "Not sent. ${started.problem}"
                app.say("Not sent. ${started.problem}")
                checking = null
            }
        }
    }
}

/** Screens that belong to Texts, where "Sent" is said aloud. */
val Screen.isTextsScreen: Boolean
    get() = this is Screen.Texts || this is Screen.Conversation || this is Screen.Compose || this is Screen.QuickReply ||
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
