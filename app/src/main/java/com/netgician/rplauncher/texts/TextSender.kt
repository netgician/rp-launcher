package com.netgician.rplauncher.texts

import com.netgician.rplauncher.i18n.tr
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.netgician.rplauncher.mms.Addresses
import com.netgician.rplauncher.mms.Attachment
import com.netgician.rplauncher.mms.MmsCodec
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sends texts and picture texts, saves them in the phone's message store first (marked
 * "sending"), and turns every result into plain words: "Sent", or "Not sent" and why.
 */
object TextSender {
    const val ACTION_SMS_SENT = "com.netgician.rplauncher.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.netgician.rplauncher.SMS_DELIVERED"
    const val ACTION_MMS_SENT = "com.netgician.rplauncher.MMS_SENT"
    const val ACTION_NOTIFY_SENT = "com.netgician.rplauncher.MMS_NOTIFY_SENT"

    /** How a send started. The final result arrives later, as a spoken "Sent" or "Not sent". */
    data class Started(val threadId: Long, val ok: Boolean, val problem: String? = null)

    fun subId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    @Suppress("DEPRECATION")
    fun smsManager(context: Context, subId: Int): SmsManager {
        val valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (valid) base.createForSubscriptionId(subId) else base
        } else {
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    /** The carrier's largest picture text, in bytes. Most allow about 1 MB; some much less. */
    fun maxMmsBytes(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        smsManager(context, subId()).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)
    }.getOrDefault(300 * 1024).coerceIn(100 * 1024, 5 * 1024 * 1024)

    private fun notifyWapMmsc(context: Context, subId: Int): Boolean = runCatching {
        @Suppress("DEPRECATION")
        smsManager(context, subId).carrierConfigValues.getBoolean(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED, false)
    }.getOrDefault(false)

    /**
     * Sends [text] and [attachments] to one person or a group. One person and only words is a
     * plain text; anything else (a group, a picture, a voice message, an email address) is a
     * picture text, so replies reach everyone.
     */
    fun send(context: Context, recipients: List<String>, text: String, attachments: List<Attachment> = emptyList()): Started {
        val to = Addresses.distinct(recipients).filter { it.isNotBlank() }
        val store = TextStore(context)
        if (to.isEmpty()) return Started(-1, false, tr("There's no one to send it to."))
        if (text.isBlank() && attachments.isEmpty()) return Started(-1, false, tr("There's nothing to send."))
        val thread = runCatching { store.threadFor(to) }.getOrElse { return Started(-1, false, tr("The phone's message store isn't available.")) }
        val plain = to.size == 1 && attachments.isEmpty() && !to[0].contains('@')
        return if (plain) sendSms(context, to[0], text.trim(), thread) else sendMms(context, to, text.trim(), attachments, thread)
    }

    // ---------------------------------------------------------------- texts

    private fun sendSms(context: Context, to: String, body: String, threadId: Long): Started {
        val subId = subId()
        val uri = TextStore(context).saveOutgoingSms(to, body, threadId, subId)
            ?: return Started(threadId, false, tr("The phone's message store isn't available."))
        val id = uri.lastPathSegment?.toLongOrNull() ?: return Started(threadId, false, tr("The phone's message store isn't available."))
        TextsBus.changed()
        dispatchSms(context, id, to, body, subId, threadId)
        return Started(threadId, true)
    }

    private fun dispatchSms(context: Context, id: Long, to: String, body: String, subId: Int, threadId: Long) {
        try {
            val sms = smsManager(context, subId)
            val parts = sms.divideMessage(body)
            SendRecords.startParts(context, id, parts.size)
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent += result(context, ACTION_SMS_SENT, "rp://sms/$id/$i/${parts.size}")
                delivered += result(context, ACTION_SMS_DELIVERED, "rp://sms/$id/$i/${parts.size}")
            }
            // Digits only, as the network wants them; names like "AMAZON" are left as they are.
            val dest = Addresses.forSending(to).ifEmpty { to }
            if (parts.size <= 1) sms.sendTextMessage(dest, null, body, sent[0], delivered[0])
            else sms.sendMultipartTextMessage(dest, null, parts, sent, delivered)
        } catch (e: Exception) {
            finishSms(context, id, SmsManager.RESULT_ERROR_GENERIC_FAILURE, threadId)
        }
    }

    private fun result(context: Context, action: String, data: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(action, Uri.parse(data), context, TextResultReceiver::class.java),
            // Mutable: the phone adds the carrier's reply to it.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun onSmsSent(context: Context, data: Uri, resultCode: Int) {
        val id = data.pathSegments.getOrNull(0)?.toLongOrNull() ?: return
        val total = SendRecords.partResult(context, id, resultCode) ?: return
        val thread = TextStore(context).smsRow(id)?.third ?: -1L
        finishSms(context, id, total, thread)
    }

    private fun finishSms(context: Context, id: Long, resultCode: Int, threadId: Long) {
        val store = TextStore(context)
        if (resultCode == Activity.RESULT_OK) {
            store.setSmsBox(id, Telephony.Sms.MESSAGE_TYPE_SENT)
            SendRecords.clearProblem(context, "sms:$id")
            report(context, threadId, ok = true, words = tr("Sent"))
        } else {
            val words = Problems.sms(resultCode)
            store.setSmsBox(id, Telephony.Sms.MESSAGE_TYPE_FAILED, resultCode)
            SendRecords.setProblem(context, "sms:$id", words)
            report(context, threadId, ok = false, words = words)
        }
    }

    fun onSmsDelivered(context: Context, intent: Intent) {
        val data = intent.data ?: return
        val id = data.pathSegments.getOrNull(0)?.toLongOrNull() ?: return
        val part = data.pathSegments.getOrNull(1)?.toIntOrNull() ?: 0
        val parts = data.pathSegments.getOrNull(2)?.toIntOrNull() ?: 1
        if (part != parts - 1) return
        val pdu = intent.getByteArrayExtra("pdu") ?: return
        val status = runCatching { SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))?.status }.getOrNull() ?: return
        // 0-31 is "delivered" for GSM; CDMA reports 0 for delivered.
        if (status in 0..0x1F) {
            TextStore(context).setSmsDelivered(id, true)
            TextsBus.changed()
        }
    }

    // ---------------------------------------------------------------- picture texts

    private fun sendMms(context: Context, to: List<String>, text: String, attachments: List<Attachment>, threadId: Long): Started {
        val subId = subId()
        val parts = attachments.mapIndexed { i, a ->
            TextStore.OutPart(a.contentType, a.fileName ?: "part$i", null, a.data)
        } + listOfNotNull(text.takeIf { it.isNotBlank() }?.let { TextStore.OutPart("text/plain", "text.txt", it, null) })
        val id = TextStore(context).saveOutgoingMms(to, parts, threadId, subId, null)
            ?: return Started(threadId, false, tr("The phone's message store isn't available."))
        TextsBus.changed()
        dispatchMms(context, id, to, text, attachments, subId, threadId)
        return Started(threadId, true)
    }

    private fun dispatchMms(context: Context, id: Long, to: List<String>, text: String, attachments: List<Attachment>, subId: Int, threadId: Long) {
        val bytes = MmsCodec.buildSend(to, text, attachments)
        if (bytes == null) {
            failMms(context, id, threadId, tr("It couldn't be put together."), 0)
            return
        }
        if (bytes.size > maxMmsBytes(context)) {
            failMms(context, id, threadId, Problems.TOO_BIG, 0)
            return
        }
        try {
            val file = File(context.cacheDir, "mms/send-$id.pdu").apply { parentFile?.mkdirs(); writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            smsManager(context, subId).sendMultimediaMessage(context, uri, null, null, result(context, ACTION_MMS_SENT, "rp://mms/$id"))
        } catch (e: Exception) {
            failMms(context, id, threadId, Problems.mms(SmsManager.MMS_ERROR_UNSPECIFIED), 0)
        }
    }

    fun onMmsSent(context: Context, intent: Intent, resultCode: Int) {
        val id = intent.data?.pathSegments?.getOrNull(0)?.toLongOrNull() ?: return
        File(context.cacheDir, "mms/send-$id.pdu").delete()
        val store = TextStore(context)
        val thread = store.threadOfMms(id)
        val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let { MmsCodec.readSendResult(it) }
        if (resultCode == Activity.RESULT_OK && (conf == null || conf.ok)) {
            store.setMmsBox(id, Telephony.Mms.MESSAGE_BOX_SENT, conf?.status ?: 0, conf?.messageId)
            SendRecords.clearProblem(context, "mms:$id")
            report(context, thread, ok = true, words = tr("Sent"))
        } else {
            val words = if (resultCode != Activity.RESULT_OK) Problems.mms(resultCode) else conf?.reason ?: Problems.mms(0)
            failMms(context, id, thread, words, conf?.status ?: 0)
        }
    }

    private fun failMms(context: Context, id: Long, threadId: Long, words: String, status: Int) {
        TextStore(context).setMmsBox(id, Telephony.Mms.MESSAGE_BOX_FAILED, status)
        SendRecords.setProblem(context, "mms:$id", words)
        report(context, threadId, ok = false, words = words)
    }

    /** Tells the carrier what happened to a picture text it said was waiting. */
    fun sendCarrierReply(context: Context, bytes: ByteArray, subId: Int, location: String?, name: String) {
        runCatching {
            val file = File(context.cacheDir, "mms/$name.pdu").apply { parentFile?.mkdirs(); writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val url = if (location != null && notifyWapMmsc(context, subId)) location else null
            smsManager(context, subId).sendMultimediaMessage(
                context, uri, url, null,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_NOTIFY_SENT, Uri.parse("rp://reply/$name"), context, TextResultReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    fun onCarrierReplySent(context: Context, intent: Intent) {
        val name = intent.data?.lastPathSegment ?: return
        File(context.cacheDir, "mms/$name.pdu").delete()
    }

    // ---------------------------------------------------------------- trying again

    /** Sends a text that wasn't sent, again. */
    fun retry(context: Context, m: TextMessage) {
        val store = TextStore(context)
        val subId = subId()
        if (!m.isMms) {
            val (to, body, thread) = store.smsRow(m.id) ?: return
            store.setSmsBox(m.id, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            SendRecords.clearProblem(context, "sms:${m.id}")
            TextsBus.changed()
            dispatchSms(context, m.id, to, body, subId, thread)
        } else {
            val (to, parts) = store.outgoingMms(m.id) ?: return
            val thread = store.threadOfMms(m.id)
            store.setMmsBox(m.id, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            SendRecords.clearProblem(context, "mms:${m.id}")
            TextsBus.changed()
            val text = parts.filter { it.text != null }.joinToString("\n") { it.text.orEmpty() }
            val attachments = parts.filter { it.data != null }.map { Attachment(it.contentType, it.name, it.data!!) }
            dispatchMms(context, m.id, to, text, attachments, subId, thread)
        }
    }

    /** Says the result if RP Launcher is on screen; a failure also becomes a notification, so it's never missed. */
    private fun report(context: Context, threadId: Long, ok: Boolean, words: String) {
        TextsBus.post(TextsBus.Event.Sent(threadId, ok, words))
        if (!ok && !(TextsBus.foreground && TextsBus.openThread == threadId)) {
            val who = TextStore(context).recipients(threadId).let { list -> Names(context).let { n -> People.join(list.map(n::of)) } }
            TextNotifications.problem(context, threadId, tr("Text to %s not sent", who), tr("%s Open Texts and double tap Try again.", words))
        }
    }
}

/** Shrinks a photo until it fits in a picture text. */
object Photos {
    /** A JPEG of the picture at [uri], small enough for [budgetBytes], or null if it can't be read. */
    fun fit(context: Context, uri: Uri, budgetBytes: Int): ByteArray? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var bitmap: Bitmap? = null
        for (step in PhotoFit.steps) {
            val b = bitmap ?: ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = max(info.size.width, info.size.height)
                if (long > PhotoFit.steps.first().longSide * 2) decoder.setTargetSampleSize(long / (PhotoFit.steps.first().longSide))
            }.also { bitmap = it }
            val scaled = scale(b, step.longSide)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, out)
            if (scaled !== b) scaled.recycle()
            if (out.size() <= budgetBytes) return@runCatching out.toByteArray()
        }
        null
    }.getOrNull()

    private fun scale(b: Bitmap, longSide: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= longSide) return b
        val f = longSide.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
    }
}
