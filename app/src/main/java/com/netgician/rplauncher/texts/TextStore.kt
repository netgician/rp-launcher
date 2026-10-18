package com.netgician.rplauncher.texts

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.netgician.rplauncher.mms.Addresses
import com.netgician.rplauncher.mms.MmsMessage
import com.netgician.rplauncher.mms.MmsNotice
import com.netgician.rplauncher.mms.PartKind
import com.netgician.rplauncher.mms.PartKinds

/**
 * The phone's shared message store (the same one every texting app uses). As the texting app,
 * RP Launcher writes every text it sends or receives here, so nothing is lost if the person
 * switches back to another texting app. All calls block: run them off the main thread.
 */
class TextStore(private val context: Context) {
    private val resolver = context.contentResolver

    companion object {
        val SMS: Uri = Uri.parse("content://sms")
        val SMS_INBOX: Uri = Uri.parse("content://sms/inbox")
        val SMS_OUTBOX: Uri = Uri.parse("content://sms/outbox")
        val MMS: Uri = Uri.parse("content://mms")
        val MMS_PART: Uri = Uri.parse("content://mms/part")
        val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")

        // MMS message types and address types, from the MMS format.
        const val M_SEND_REQ = 128
        const val M_NOTIFICATION = 130
        const val M_RETRIEVE_CONF = 132
        const val ADDR_FROM = 137
        const val ADDR_TO = 151
        const val ADDR_CC = 130
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"

        fun partUri(partId: Long): Uri = Uri.parse("content://mms/part/$partId")
        fun smsUri(id: Long): Uri = Uri.parse("content://sms/$id")
        fun mmsUri(id: Long): Uri = Uri.parse("content://mms/$id")
    }

    // ---------------------------------------------------------------- reading

    /** Conversations, newest first, with each person's name from [names]. */
    fun conversations(names: (String) -> String, limit: Int = 100): List<Conversation> {
        val canonical = mutableMapOf<String, String>()
        resolver.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getString(0)] = c.getString(1).orEmpty()
        }
        val unread = unreadByThread()
        val latest = latestWordsByThread()
        val failed = failedThreads()
        val out = mutableListOf<Conversation>()
        resolver.query(
            CONVERSATIONS,
            arrayOf("_id", "date", "recipient_ids", "snippet", "error"),
            null, null, "date DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val addresses = Addresses.distinct(
                    c.getString(2).orEmpty().split(' ').mapNotNull { canonical[it] }.filter { it.isNotBlank() },
                )
                if (addresses.isEmpty()) continue
                out += Conversation(
                    threadId = id,
                    addresses = addresses,
                    names = addresses.map(names),
                    snippet = latest[id] ?: c.getString(3).orEmpty(),
                    date = c.getLong(1),
                    unread = unread[id] ?: 0,
                    hasProblem = c.getInt(4) != 0 || id in failed,
                )
            }
        }
        return out
    }

    private fun failedThreads(): Set<Long> = buildSet {
        resolver.query(SMS, arrayOf("thread_id"), "type = ${Telephony.Sms.MESSAGE_TYPE_FAILED}", null, null)?.use { c ->
            while (c.moveToNext()) add(c.getLong(0))
        }
        resolver.query(MMS, arrayOf("thread_id"), "msg_box = ${Telephony.Mms.MESSAGE_BOX_FAILED}", null, null)?.use { c ->
            while (c.moveToNext()) add(c.getLong(0))
        }
    }

    /**
     * Rows matching [base] that count as new: unread texts that arrived since RP Launcher became
     * the texting app (saved by it, or dated after the switch). Texts the old app left marked
     * unread in the shared store, often thousands, don't count; that app kept its own record.
     */
    private fun queryNew(uri: Uri, projection: Array<String>, base: String, seconds: Boolean, each: (Cursor) -> Unit) {
        val since = SendRecords.since(context).let { if (seconds) it / 1000 else it }
        try {
            resolver.query(uri, projection, "$base AND (creator = ? OR date >= $since)", arrayOf(context.packageName), null)
                ?.use { c -> while (c.moveToNext()) each(c) }
        } catch (e: Exception) {
            // Some phones' stores have no "creator" column: go by date alone.
            resolver.query(uri, projection, "$base AND date >= $since", null, null)?.use { c -> while (c.moveToNext()) each(c) }
        }
    }

    private fun unreadByThread(): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        queryNew(SMS, arrayOf("thread_id"), "read = 0 AND type = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", seconds = false) { c ->
            counts.merge(c.getLong(0), 1, Int::plus)
        }
        queryNew(MMS, arrayOf("thread_id"), "read = 0 AND msg_box = ${Telephony.Mms.MESSAGE_BOX_INBOX}", seconds = true) { c ->
            counts.merge(c.getLong(0), 1, Int::plus)
        }
        return counts
    }

    /** Total unread texts, for the Texts tile. */
    fun unreadCount(): Int = unreadByThread().values.sum()

    /** The newest words in each conversation. The store's own snippet leaves out picture-text words. */
    private fun latestWordsByThread(): Map<Long, String> {
        data class Latest(val date: Long, val words: String)
        val best = mutableMapOf<Long, Latest>()
        resolver.query(SMS, arrayOf("thread_id", "date", "body"), null, null, "date DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 500) {
                val t = c.getLong(0)
                if (t !in best) best[t] = Latest(c.getLong(1), c.getString(2).orEmpty())
            }
        }
        val mmsLatest = mutableMapOf<Long, Pair<Long, Long>>() // thread → (mms id, date ms)
        resolver.query(MMS, arrayOf("thread_id", "_id", "date", "m_type"), null, null, "date DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 300) {
                val t = c.getLong(0)
                if (t !in mmsLatest) mmsLatest[t] = c.getLong(1) to c.getLong(2) * 1000
            }
        }
        val newerMms = mmsLatest.filter { (t, v) -> (best[t]?.date ?: 0L) < v.second }
        if (newerMms.isNotEmpty()) {
            val words = mmsWords(newerMms.values.map { it.first })
            val kinds = mmsAttachmentWords(newerMms.values.map { it.first })
            for ((t, v) in newerMms) {
                val w = words[v.first]?.takeIf { it.isNotBlank() } ?: kinds[v.first] ?: "Picture text"
                best[t] = Latest(v.second, w)
            }
        }
        return best.mapValues { it.value.words }
    }

    private fun mmsWords(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, String>()
        resolver.query(
            MMS_PART, arrayOf("mid", "text"),
            "ct = 'text/plain' AND mid IN (${ids.joinToString(",")})", null, "seq",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out[id] = listOfNotNull(out[id], c.getString(1)).joinToString(" ")
            }
        }
        return out
    }

    private fun mmsAttachmentWords(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val parts = mutableMapOf<Long, MutableList<TextPart>>()
        resolver.query(
            MMS_PART, arrayOf("_id", "mid", "ct", "name", "fn", "cl"),
            "mid IN (${ids.joinToString(",")})", null, "seq",
        )?.use { c ->
            while (c.moveToNext()) partFrom(c, 0)?.let { parts.getOrPut(c.getLong(1)) { mutableListOf() } += it }
        }
        return parts.mapValues { TextSpeech.attachments(it.value).removeSuffix(".") }
    }

    /** Messages in a conversation, oldest first. */
    fun messages(threadId: Long, downloads: (Long) -> Pair<DownloadState, String?>, limit: Int = 200): List<TextMessage> {
        val out = mutableListOf<TextMessage>()
        val newSms = mutableSetOf<Long>()
        val newMms = mutableSetOf<Long>()
        queryNew(SMS, arrayOf("_id"), "thread_id = $threadId AND read = 0 AND type = ${Telephony.Sms.MESSAGE_TYPE_INBOX}", false) { newSms += it.getLong(0) }
        queryNew(MMS, arrayOf("_id"), "thread_id = $threadId AND read = 0 AND msg_box = ${Telephony.Mms.MESSAGE_BOX_INBOX}", true) { newMms += it.getLong(0) }
        resolver.query(
            SMS, arrayOf("_id", "address", "body", "date", "type", "status", "read", "error_code"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val type = c.getInt(4)
                if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                val box = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> Box.INBOX
                    Telephony.Sms.MESSAGE_TYPE_SENT -> Box.SENT
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> Box.FAILED
                    else -> Box.SENDING // outbox, queued
                }
                val id = c.getLong(0)
                out += TextMessage(
                    id = id,
                    isMms = false,
                    box = box,
                    date = c.getLong(3),
                    body = c.getString(2).orEmpty(),
                    from = if (box == Box.INBOX) c.getString(1) else null,
                    delivered = c.getInt(5) == Telephony.Sms.STATUS_COMPLETE,
                    problem = if (box == Box.FAILED) SendRecords.problem(context, "sms:$id") ?: Problems.sms(c.getInt(7)) else null,
                    read = id !in newSms,
                )
            }
        }
        val mms = mutableListOf<Long>()
        val rows = mutableMapOf<Long, TextMessage>()
        resolver.query(
            MMS, arrayOf("_id", "date", "msg_box", "m_type", "sub", "read", "st"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val id = c.getLong(0)
                val mType = c.getInt(3)
                val boxCol = c.getInt(2)
                val box = when {
                    mType == M_NOTIFICATION -> Box.WAITING
                    boxCol == Telephony.Mms.MESSAGE_BOX_INBOX -> Box.INBOX
                    boxCol == Telephony.Mms.MESSAGE_BOX_SENT -> Box.SENT
                    boxCol == Telephony.Mms.MESSAGE_BOX_FAILED -> Box.FAILED
                    boxCol == Telephony.Mms.MESSAGE_BOX_DRAFTS -> Box.DRAFT
                    else -> Box.SENDING
                }
                if (box == Box.DRAFT) continue
                val (dl, why) = if (box == Box.WAITING) downloads(id) else DownloadState.NOT_STARTED to null
                mms += id
                rows[id] = TextMessage(
                    id = id,
                    isMms = true,
                    box = box,
                    date = c.getLong(1) * 1000,
                    body = "",
                    from = null,
                    subject = c.getString(4)?.takeIf { it.isNotBlank() },
                    delivered = c.getInt(6) == 129, // X-Mms-Status: retrieved
                    problem = when (box) {
                        Box.FAILED -> SendRecords.problem(context, "mms:$id") ?: "The carrier could not send it."
                        Box.WAITING -> why
                        else -> null
                    },
                    download = dl,
                    read = id !in newMms,
                )
            }
        }
        if (mms.isNotEmpty()) {
            val senders = mmsSenders(mms)
            val parts = mutableMapOf<Long, MutableList<TextPart>>()
            val words = mutableMapOf<Long, String>()
            resolver.query(
                MMS_PART, arrayOf("_id", "mid", "ct", "name", "fn", "cl", "text"),
                "mid IN (${mms.joinToString(",")})", null, "seq",
            )?.use { c ->
                while (c.moveToNext()) {
                    val mid = c.getLong(1)
                    val ct = c.getString(2).orEmpty().lowercase()
                    if (ct == "text/plain") {
                        words[mid] = listOfNotNull(words[mid], c.getString(6)).joinToString("\n")
                    } else {
                        partFrom(c, 0)?.let { parts.getOrPut(mid) { mutableListOf() } += it }
                    }
                }
            }
            for ((id, m) in rows) {
                val sender = senders[id]?.takeIf { it != INSERT_ADDRESS_TOKEN }
                out += m.copy(
                    body = words[id].orEmpty(),
                    parts = parts[id].orEmpty(),
                    from = if (m.box == Box.INBOX || m.box == Box.WAITING) (sender ?: "") else null,
                )
            }
        }
        return out.sortedBy { it.date }
    }

    private fun mmsSenders(ids: List<Long>): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        for (id in ids) {
            resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = $ADDR_FROM", null, null)?.use { c ->
                if (c.moveToFirst()) out[id] = Addresses.clean(c.getString(0))
            }
        }
        return out
    }

    /** A stored part, unless it's the words or the layout (those are shown as the message itself). */
    private fun partFrom(c: Cursor, idCol: Int): TextPart? {
        val id = c.getLong(idCol)
        val ct = c.getString(c.getColumnIndexOrThrow("ct")).orEmpty().lowercase()
        val name = listOf("fn", "name", "cl").firstNotNullOfOrNull { col ->
            c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
        }
        val kind = PartKinds.of(ct, name)
        if (kind == PartKind.TEXT || kind == PartKind.LAYOUT) return null
        return TextPart(id, kind, PartKinds.typeOf(ct, name), name, partUri(id).toString())
    }

    /** Everyone in a conversation, for replying. */
    fun recipients(threadId: Long): List<String> {
        val ids = resolver.query(CONVERSATIONS, arrayOf("recipient_ids"), "_id = ?", arrayOf(threadId.toString()), null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty().split(' ') else emptyList() }.orEmpty()
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        resolver.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(0) in ids) out += c.getString(1).orEmpty()
        }
        return Addresses.distinct(out)
    }

    fun readPart(partId: Long): ByteArray? = runCatching {
        resolver.openInputStream(partUri(partId))?.use { it.readBytes() }
    }.getOrNull()

    fun markRead(threadId: Long) {
        val v = ContentValues().apply { put("read", 1); put("seen", 1) }
        runCatching { resolver.update(SMS, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString())) }
        runCatching { resolver.update(MMS, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString())) }
    }

    fun threadFor(addresses: Collection<String>): Long =
        Telephony.Threads.getOrCreateThreadId(context, addresses.toSet())

    // ---------------------------------------------------------------- texts (SMS)

    /** Saves a received text, unread. Returns its row. */
    fun saveReceivedSms(from: String, body: String, sentAt: Long, subId: Int): Uri? {
        val v = ContentValues().apply {
            put("address", from)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("date_sent", sentAt)
            put("read", 0)
            put("seen", 0)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("thread_id", threadFor(listOf(from)))
            if (subId >= 0) put("sub_id", subId)
        }
        return resolver.insert(SMS_INBOX, v)
    }

    /** Saves a text we're about to send, marked as sending. */
    fun saveOutgoingSms(to: String, body: String, threadId: Long, subId: Int): Uri? {
        val v = ContentValues().apply {
            put("address", to)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("read", 1)
            put("seen", 1)
            put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put("thread_id", threadId)
            if (subId >= 0) put("sub_id", subId)
        }
        return resolver.insert(SMS_OUTBOX, v)
    }

    fun setSmsBox(id: Long, type: Int, errorCode: Int = 0) {
        val v = ContentValues().apply {
            put("type", type)
            if (errorCode != 0) put("error_code", errorCode)
            // Sending again counts from now.
            if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX) put("date", System.currentTimeMillis())
        }
        runCatching { resolver.update(smsUri(id), v, null, null) }
    }

    fun setSmsDelivered(id: Long, ok: Boolean) {
        val v = ContentValues().apply { put("status", if (ok) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED) }
        runCatching { resolver.update(smsUri(id), v, null, null) }
    }

    fun smsRow(id: Long): Triple<String, String, Long>? =
        resolver.query(smsUri(id), arrayOf("address", "body", "thread_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) else null
        }

    fun threadOfMms(id: Long): Long =
        resolver.query(mmsUri(id), arrayOf("thread_id"), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

    fun delete(message: TextMessage) {
        runCatching { resolver.delete(if (message.isMms) mmsUri(message.id) else smsUri(message.id), null, null) }
    }

    // ---------------------------------------------------------------- picture texts (MMS)

    /** True if this "picture text waiting" push was already saved (carriers sometimes send it twice). */
    fun hasNotice(location: String): Boolean =
        resolver.query(MMS, arrayOf("_id"), "ct_l = ?", arrayOf(location), null)?.use { it.count > 0 } ?: false

    /** Saves a "picture text waiting" row, so it shows (and isn't lost) before it downloads. */
    fun saveNotice(n: MmsNotice, subId: Int): Long? {
        val from = n.from ?: ""
        val thread = threadFor(listOf(from.ifEmpty { "Unknown" }))
        val v = ContentValues().apply {
            put("thread_id", thread)
            put("date", System.currentTimeMillis() / 1000)
            put("msg_box", Telephony.Mms.MESSAGE_BOX_INBOX)
            put("read", 0)
            put("seen", 0)
            put("m_type", M_NOTIFICATION)
            put("ct_l", n.contentLocation)
            put("tr_id", String(n.transactionId, Charsets.ISO_8859_1))
            put("exp", n.expiresAtSeconds)
            put("m_size", n.sizeBytes)
            put("v", n.mmsVersion)
            put("m_cls", "personal")
            n.subject?.let { put("sub", it); put("sub_cs", 106) }
            if (subId >= 0) put("sub_id", subId)
        }
        val uri = resolver.insert(MMS, v) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        if (from.isNotEmpty()) addAddress(id, from, ADDR_FROM)
        return id
    }

    data class Notice(val id: Long, val location: String, val transactionId: String, val version: Int, val subId: Int, val from: String?)

    fun notice(id: Long): Notice? =
        resolver.query(mmsUri(id), arrayOf("ct_l", "tr_id", "v", "sub_id", "m_type"), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(4) != M_NOTIFICATION) null
            else Notice(id, c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2), c.getInt(3), mmsSenders(listOf(id))[id])
        }

    /** Every picture text still waiting to download. */
    fun waitingNotices(sinceSeconds: Long = 0): List<Long> = buildList {
        resolver.query(MMS, arrayOf("_id"), "m_type = $M_NOTIFICATION AND date >= $sinceSeconds", null, null)?.use { c ->
            while (c.moveToNext()) add(c.getLong(0))
        }
    }

    /**
     * Saves a downloaded picture text in its conversation (the whole group, for group texts),
     * then removes its "waiting" row. Returns the new message id and its conversation.
     */
    fun saveReceivedMms(m: MmsMessage, myNumbers: Collection<String>, subId: Int, noticeId: Long?): Pair<Long, Long>? {
        val others = m.others(myNumbers).ifEmpty { listOfNotNull(m.from).ifEmpty { listOf("Unknown") } }
        val thread = threadFor(others)
        val v = ContentValues().apply {
            put("thread_id", thread)
            put("date", System.currentTimeMillis() / 1000)
            if (m.dateSeconds > 0) put("date_sent", m.dateSeconds)
            put("msg_box", Telephony.Mms.MESSAGE_BOX_INBOX)
            put("read", 0)
            put("seen", 0)
            put("m_type", M_RETRIEVE_CONF)
            put("v", m.mmsVersion)
            put("ct_t", "application/vnd.wap.multipart.related")
            put("m_cls", "personal")
            m.messageId?.let { put("m_id", it) }
            m.transactionId?.let { put("tr_id", String(it, Charsets.ISO_8859_1)) }
            m.subject?.let { put("sub", it); put("sub_cs", 106) }
            put("text_only", if (m.attachments.isEmpty()) 1 else 0)
            if (subId >= 0) put("sub_id", subId)
        }
        val uri = resolver.insert(MMS, v) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        m.parts.forEachIndexed { i, p ->
            val kind = p.kind
            savePart(
                msgId = id,
                seq = i,
                contentType = PartKinds.typeOf(p.contentType, p.fileName),
                name = p.safeFileName(i),
                text = if (kind == PartKind.TEXT) p.asText() else if (kind == PartKind.LAYOUT) String(p.data, Charsets.UTF_8) else null,
                data = if (kind == PartKind.TEXT || kind == PartKind.LAYOUT) null else p.data,
            )
        }
        m.from?.let { addAddress(id, it, ADDR_FROM) }
        m.to.forEach { addAddress(id, it, ADDR_TO) }
        m.cc.forEach { addAddress(id, it, ADDR_CC) }
        if (noticeId != null) runCatching { resolver.delete(mmsUri(noticeId), null, null) }
        return id to thread
    }

    /** One piece of a picture text we're sending. */
    class OutPart(val contentType: String, val name: String, val text: String?, val data: ByteArray?)

    /** Saves a picture text we're about to send, marked as sending. */
    fun saveOutgoingMms(to: List<String>, parts: List<OutPart>, threadId: Long, subId: Int, subject: String?): Long? {
        val v = ContentValues().apply {
            put("thread_id", threadId)
            put("date", System.currentTimeMillis() / 1000)
            put("msg_box", Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put("read", 1)
            put("seen", 1)
            put("m_type", M_SEND_REQ)
            put("v", 0x12)
            put("ct_t", "application/vnd.wap.multipart.related")
            put("m_cls", "personal")
            put("pri", 129)
            put("exp", 604_800)
            put("d_rpt", 129)
            put("rr", 129)
            put("text_only", if (parts.all { it.text != null }) 1 else 0)
            subject?.let { put("sub", it); put("sub_cs", 106) }
            if (subId >= 0) put("sub_id", subId)
        }
        val uri = resolver.insert(MMS, v) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        parts.forEachIndexed { i, p -> savePart(id, i, p.contentType, p.name, p.text, p.data) }
        addAddress(id, INSERT_ADDRESS_TOKEN, ADDR_FROM)
        to.forEach { addAddress(id, it, ADDR_TO) }
        return id
    }

    fun outgoingMms(id: Long): Pair<List<String>, List<OutPart>>? {
        val to = mutableListOf<String>()
        resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = $ADDR_TO", null, null)?.use { c ->
            while (c.moveToNext()) to += c.getString(0).orEmpty()
        }
        val parts = mutableListOf<OutPart>()
        resolver.query(MMS_PART, arrayOf("_id", "ct", "name", "fn", "cl", "text"), "mid = ?", arrayOf(id.toString()), "seq")?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getString(1).orEmpty().lowercase()
                if (ct == "application/smil") continue
                val name = c.getString(3) ?: c.getString(2) ?: c.getString(4) ?: "part"
                parts += if (ct == "text/plain") OutPart(ct, name, c.getString(5).orEmpty(), null)
                else OutPart(ct, name, null, readPart(c.getLong(0)) ?: continue)
            }
        }
        return if (to.isEmpty()) null else to to parts
    }

    /**
     * Texts still marked "sending" long after they should have finished (the phone restarted
     * mid-send, say) are marked not sent, so they show Try again instead of waiting forever.
     */
    fun failStuckOutgoing(olderThanMs: Long, problem: (String) -> Unit): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var n = 0
        val sms = mutableListOf<Long>()
        resolver.query(
            SMS, arrayOf("_id"),
            "type IN (${Telephony.Sms.MESSAGE_TYPE_OUTBOX}, ${Telephony.Sms.MESSAGE_TYPE_QUEUED}) AND date < $cutoff", null, null,
        )?.use { c -> while (c.moveToNext()) sms += c.getLong(0) }
        sms.forEach { id -> setSmsBox(id, Telephony.Sms.MESSAGE_TYPE_FAILED); problem("sms:$id"); n++ }
        val mms = mutableListOf<Long>()
        resolver.query(
            MMS, arrayOf("_id"),
            "msg_box = ${Telephony.Mms.MESSAGE_BOX_OUTBOX} AND date < ${cutoff / 1000}", null, null,
        )?.use { c -> while (c.moveToNext()) mms += c.getLong(0) }
        mms.forEach { id -> setMmsBox(id, Telephony.Mms.MESSAGE_BOX_FAILED); problem("mms:$id"); n++ }
        return n
    }

    fun setMmsBox(id: Long, box: Int, responseStatus: Int = 0, messageId: String? = null) {
        val v = ContentValues().apply {
            put("msg_box", box)
            if (responseStatus != 0) put("resp_st", responseStatus)
            messageId?.let { put("m_id", it) }
            if (box == Telephony.Mms.MESSAGE_BOX_SENT || box == Telephony.Mms.MESSAGE_BOX_OUTBOX) put("date", System.currentTimeMillis() / 1000)
        }
        runCatching { resolver.update(mmsUri(id), v, null, null) }
    }

    /** A delivery report: marks the sent picture text with this message id as delivered (or not). */
    fun setMmsDelivered(messageId: String, status: Int): Long? {
        val id = resolver.query(MMS, arrayOf("_id"), "m_id = ? AND msg_box = ${Telephony.Mms.MESSAGE_BOX_SENT}", arrayOf(messageId), null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        runCatching { resolver.update(mmsUri(id), ContentValues().apply { put("st", status) }, null, null) }
        return id
    }

    private fun savePart(msgId: Long, seq: Int, contentType: String, name: String, text: String?, data: ByteArray?) {
        val v = ContentValues().apply {
            put("mid", msgId)
            put("seq", if (contentType == "application/smil") -1 else seq)
            put("ct", contentType)
            put("name", name)
            put("fn", name)
            put("cl", name)
            put("cid", "<$name>")
            if (text != null) {
                put("chset", 106)
                put("text", text)
            }
        }
        val uri = resolver.insert(Uri.parse("content://mms/$msgId/part"), v) ?: return
        if (data != null) runCatching { resolver.openOutputStream(uri)?.use { it.write(data) } }
    }

    private fun addAddress(msgId: Long, address: String, type: Int) {
        val v = ContentValues().apply {
            put("msg_id", msgId)
            put("address", address)
            put("type", type)
            put("charset", 106)
        }
        runCatching { resolver.insert(Uri.parse("content://mms/$msgId/addr"), v) }
    }
}
