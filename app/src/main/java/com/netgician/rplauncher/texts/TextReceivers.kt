package com.netgician.rplauncher.texts

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.netgician.rplauncher.MainActivity
import com.netgician.rplauncher.mms.MmsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runs receiver work off the main thread, keeping the receiver alive until it's done. */
private val textScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    textScope.launch {
        try {
            block()
        } catch (_: Throwable) {
            // Never crash the phone's texting: a failed save is reported where possible, otherwise dropped.
        } finally {
            pending.finish()
        }
    }
}

private fun subIdOf(intent: Intent): Int =
    intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))

/** Says a new text arrived: aloud if RP Launcher is on screen, and as a notification. */
internal fun announceIncoming(context: Context, threadId: Long, from: String, words: String) {
    val who = Names(context).of(from)
    TextsBus.post(TextsBus.Event.Received(threadId, who, words))
    TextNotifications.incoming(context, threadId, who, words)
}

/** New texts (SMS). Only the phone's texting app gets these. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        work {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return@work
            if (parts.isEmpty()) return@work
            val from = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: return@work
            val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            val store = TextStore(context)
            val uri = runCatching { store.saveReceivedSms(from, body, parts[0].timestampMillis, subIdOf(intent)) }.getOrNull()
            val thread = uri?.lastPathSegment?.toLongOrNull()?.let { id -> runCatching { store.smsRow(id)?.third }.getOrNull() }
                ?: runCatching { store.threadFor(listOf(from)) }.getOrDefault(-1L)
            if (uri == null) {
                // Couldn't be saved: show it anyway, so it's never lost without a word.
                TextNotifications.problem(context, thread, "Text from ${Names(context).of(from)} (not saved)", body)
            }
            announceIncoming(context, thread, from, body)
        }
    }
}

/** "A picture text is waiting" pushes, and delivery reports for picture texts we sent. */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = subIdOf(intent)
        work {
            val store = TextStore(context)
            when (MmsCodec.messageType(data)) {
                0x82 -> { // notification-ind
                    val n = MmsCodec.readNotice(data) ?: return@work
                    if (store.hasNotice(n.contentLocation) || SendRecords.wasFetched(context, n.contentLocation)) return@work
                    val id = store.saveNotice(n, subId) ?: return@work
                    TextsBus.changed()
                    MmsDownloads.start(context, id, attempt = 1)
                }
                0x86 -> { // delivery-ind
                    val d = MmsCodec.readDelivery(data) ?: return@work
                    if (d.delivered) d.messageId?.let { store.setMmsDelivered(it, 129) }
                    TextsBus.changed()
                }
            }
        }
    }
}

/** Fetches waiting picture texts from the carrier, tries again by itself, and says so when it can't. */
object MmsDownloads {
    const val ACTION_DOWNLOADED = "com.netgician.rplauncher.MMS_DOWNLOADED"
    const val ACTION_RETRY = "com.netgician.rplauncher.MMS_RETRY"
    const val STOPPED = "The download stopped."

    fun start(context: Context, id: Long, attempt: Int) {
        val n = TextStore(context).notice(id) ?: return
        SendRecords.setDownload(context, id, DownloadState.DOWNLOADING, attempt)
        TextsBus.changed()
        try {
            val file = File(context.cacheDir, "mms/dl-$id.pdu").apply { parentFile?.mkdirs(); delete() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val done = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_DOWNLOADED, Uri.parse("rp://dl/$id/$attempt"), context, TextResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            TextSender.smsManager(context, if (n.subId > 0) n.subId else TextSender.subId())
                .downloadMultimediaMessage(context, n.location, uri, null, done)
        } catch (e: Exception) {
            finished(context, id, attempt, SmsManager.MMS_ERROR_UNSPECIFIED)
        }
    }

    /**
     * Upkeep when RP Launcher starts: picture texts never fetched, or whose download was lost,
     * are started; texts stuck "sending" are marked not sent.
     */
    fun resume(context: Context) {
        val stuck = runCatching {
            TextStore(context).failStuckOutgoing(15 * 60_000L) { key -> SendRecords.setProblem(context, key, "It didn't finish sending.") }
        }.getOrDefault(0)
        if (stuck > 0) TextsBus.changed()
        val weekAgo = System.currentTimeMillis() / 1000 - 7 * 24 * 3600
        for (id in TextStore(context).waitingNotices(sinceSeconds = weekAgo)) {
            val (state, why) = SendRecords.download(context, id)
            val neverTried = state == DownloadState.NOT_STARTED && SendRecords.attempts(context, id) == 0
            val lost = state == DownloadState.FAILED && why == STOPPED
            // A retry whose alarm was lost (the phone restarted, say) is started again.
            val retryLost = state == DownloadState.RETRYING && SendRecords.downloadAgeMs(context, id) > 35 * 60_000L
            when {
                neverTried || lost -> start(context, id, attempt = 1)
                retryLost -> start(context, id, attempt = SendRecords.attempts(context, id) + 1)
            }
        }
    }

    fun onDownloaded(context: Context, intent: Intent, resultCode: Int) {
        val seg = intent.data?.pathSegments ?: return
        val id = seg.getOrNull(0)?.toLongOrNull() ?: return
        val attempt = seg.getOrNull(1)?.toIntOrNull() ?: 1
        finished(context, id, attempt, resultCode)
    }

    private fun finished(context: Context, id: Long, attempt: Int, resultCode: Int) {
        val file = File(context.cacheDir, "mms/dl-$id.pdu")
        val store = TextStore(context)
        val notice = store.notice(id)
        if (notice == null) { // already saved, or deleted meanwhile
            file.delete()
            SendRecords.clearDownload(context, id)
            return
        }
        val bytes = if (resultCode == Activity.RESULT_OK && file.exists()) runCatching { file.readBytes() }.getOrNull() else null
        file.delete()
        if (bytes == null || bytes.isEmpty()) {
            val words = if (resultCode == Activity.RESULT_OK) Problems.mms(SmsManager.MMS_ERROR_IO_ERROR) else Problems.mms(resultCode)
            val delay = if (Problems.mmsWorthRetrying(resultCode)) Retry.nextDelay(attempt) else null
            if (delay != null) {
                SendRecords.setDownload(context, id, DownloadState.RETRYING, attempt, words)
                scheduleRetry(context, id, attempt + 1, delay)
                TextsBus.changed()
            } else {
                giveUp(context, id, notice, attempt, words)
            }
            return
        }
        val message = MmsCodec.readMessage(bytes)
        when {
            message == null -> giveUp(context, id, notice, attempt, Problems.UNREADABLE)
            !message.retrieveOk -> giveUp(context, id, notice, attempt, Problems.GONE)
            else -> {
                // A message to just one number, with no one copied, was sent to this phone's own number.
                if (message.to.size == 1 && message.cc.isEmpty()) SendRecords.learnNumber(context, message.to[0])
                val saved = runCatching { store.saveReceivedMms(message, MyNumbers.get(context), notice.subId, id) }.getOrNull() ?: run {
                    giveUp(context, id, notice, attempt, "The phone's message store isn't available.")
                    return
                }
                SendRecords.clearDownload(context, id)
                SendRecords.markFetched(context, notice.location)
                // Tell the carrier it arrived, so it doesn't send the "waiting" push again.
                MmsCodec.notifyResponse(notice.transactionId.toByteArray(Charsets.ISO_8859_1), notice.version, MmsCodec.STATUS_RETRIEVED)
                    ?.let { TextSender.sendCarrierReply(context, it, notice.subId, notice.location, "nr-$id") }
                message.transactionId?.let { tid ->
                    MmsCodec.acknowledge(tid, notice.version)?.let { TextSender.sendCarrierReply(context, it, notice.subId, null, "ack-$id") }
                }
                val words = message.text.ifBlank {
                    TextSpeech.attachments(message.attachments.mapIndexed { i, p -> TextPart(i.toLong(), p.kind, p.contentType, p.fileName, "") })
                        .ifBlank { "Picture text" }
                }
                announceIncoming(context, saved.second, message.from ?: notice.from.orEmpty(), words)
            }
        }
    }

    private fun giveUp(context: Context, id: Long, notice: TextStore.Notice, attempt: Int, words: String) {
        SendRecords.setDownload(context, id, DownloadState.FAILED, attempt, words)
        val thread = TextStore(context).threadOfMms(id)
        val who = notice.from?.let { Names(context).of(it) } ?: "someone"
        TextsBus.post(TextsBus.Event.DownloadFailed(thread, who, words))
        if (!(TextsBus.foreground && TextsBus.openThread == thread)) {
            TextNotifications.problem(
                context, thread,
                "A picture text from $who didn't download",
                "$words Open Texts and double tap Try again.",
            )
        }
    }

    private fun scheduleRetry(context: Context, id: Long, nextAttempt: Int, minutes: Long) {
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_RETRY, Uri.parse("rp://retry/$id/$nextAttempt"), context, TextResultReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, pi)
    }

    fun onRetry(context: Context, intent: Intent) {
        val seg = intent.data?.pathSegments ?: return
        val id = seg.getOrNull(0)?.toLongOrNull() ?: return
        val attempt = seg.getOrNull(1)?.toIntOrNull() ?: 2
        // Only if it's still waiting and nobody pressed Try again meanwhile.
        if (SendRecords.download(context, id).first == DownloadState.RETRYING) start(context, id, attempt)
    }
}

/** Results from the phone: a text sent or delivered, a picture text sent or downloaded, a retry due. */
class TextResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        work {
            when (intent.action) {
                TextSender.ACTION_SMS_SENT -> intent.data?.let { TextSender.onSmsSent(context, it, code) }
                TextSender.ACTION_SMS_DELIVERED -> TextSender.onSmsDelivered(context, intent)
                TextSender.ACTION_MMS_SENT -> TextSender.onMmsSent(context, intent, code)
                TextSender.ACTION_NOTIFY_SENT -> TextSender.onCarrierReplySent(context, intent)
                MmsDownloads.ACTION_DOWNLOADED -> MmsDownloads.onDownloaded(context, intent, code)
                MmsDownloads.ACTION_RETRY -> MmsDownloads.onRetry(context, intent)
            }
        }
    }
}

/** "Can't talk now" replies when declining a call. The phone app asks the texting app to send them. */
class QuickReplyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = ComposeLink.parse(intent?.data?.schemeSpecificPart)
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE" && text.isNotBlank() && link.recipients.isNotEmpty()) {
            textScope.launch {
                runCatching { TextSender.send(applicationContext, link.recipients, text) }
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

/**
 * "Text this person" from other apps (contacts, the phone app, sharing a photo). Opens the
 * writing screen in RP Launcher with the person and anything shared already filled in.
 */
class ComposeActivity : ComponentActivity() {
    companion object {
        const val ACTION_COMPOSE = "com.netgician.rplauncher.COMPOSE"
        const val EXTRA_TO = "to"
        const val EXTRA_BODY = "body"
        const val EXTRA_FILES = "files"
        const val EXTRA_TYPES = "types"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (!TextRole.isDefault(this)) {
            // RP Launcher isn't the texting app: hand this to the one that is.
            val other = Telephony.Sms.getDefaultSmsPackage(this)
            if (other != null && i != null) runCatching { startActivity(Intent(i).setComponent(null).setPackage(other)) }
            finish()
            return
        }
        val link = ComposeLink.parse(i?.data?.schemeSpecificPart)
        val body = i?.getStringExtra("sms_body") ?: i?.getStringExtra(Intent.EXTRA_TEXT) ?: link.body
        val streams = buildList {
            if (i?.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
            }
            if (i?.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            }
        }
        lifecycleScope.launch {
            // Copy anything shared now, while this screen still has permission to read it.
            val copied = withContext(Dispatchers.IO) { streams.take(5).mapNotNull { copyIn(it) } }
            startActivity(
                Intent(this@ComposeActivity, MainActivity::class.java)
                    .setAction(ACTION_COMPOSE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(EXTRA_TO, ArrayList(link.recipients))
                    .putExtra(EXTRA_BODY, body)
                    .putStringArrayListExtra(EXTRA_FILES, ArrayList(copied.map { it.first }))
                    .putStringArrayListExtra(EXTRA_TYPES, ArrayList(copied.map { it.second })),
            )
            finish()
        }
    }

    private fun copyIn(uri: Uri): Pair<String, String>? = runCatching {
        val type = contentResolver.getType(uri) ?: "application/octet-stream"
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "in-${System.currentTimeMillis()}-${uri.hashCode().toUInt()}")
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        file.absolutePath to type
    }.getOrNull()
}
