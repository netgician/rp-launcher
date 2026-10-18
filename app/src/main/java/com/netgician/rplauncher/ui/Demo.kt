package com.netgician.rplauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netgician.rplauncher.calls.PhoneRole
import com.netgician.rplauncher.calls.RecentCall
import com.netgician.rplauncher.mms.PartKind
import com.netgician.rplauncher.texts.Box
import com.netgician.rplauncher.texts.Conversation
import com.netgician.rplauncher.texts.TextMessage
import com.netgician.rplauncher.texts.TextPart

/**
 * Demo mode: made-up texts, calls and people, so the app can be shown to someone (or
 * photographed for the app stores) without anyone's real messages. No texts are sent while it's
 * on, and the made-up people only get a pretend call screen. Real calls (contact tiles, SOS, a real
 * number typed in) still go through. Turned on and off in Setup; it also goes off after two hours
 * and when RP Launcher restarts. The numbers are 555-01xx ones, which are never given to real phones.
 */
object Demo {
    var on by mutableStateOf(false)
        private set
    private var startedAt = 0L

    /** Demo mode goes off by itself after this long, in case it's forgotten. */
    private const val LASTS_MS = 2 * 60 * 60_000L

    fun set(value: Boolean) {
        on = value
        startedAt = System.currentTimeMillis()
    }

    /** True when demo mode was on and has now run out (and turns it off). */
    fun expire(): Boolean {
        if (!on || System.currentTimeMillis() - startedAt < LASTS_MS) return false
        on = false
        return true
    }

    /** Only the made-up numbers get the pretend call screen; any real number is really called. */
    fun isDemoNumber(number: String): Boolean = on && names.keys.any { it.takeLast(10) == number.filter(Char::isDigit).takeLast(10) }

    private const val MARY = "+19195550142"
    private const val OFFICE = "+19195550177"
    private const val BOB = "+19195550111"
    private const val ANA = "+19195550123"
    const val PICTURE_ID = 9001L

    const val CALLER_NAME = "Mary"
    const val CALLER_NUMBER = MARY

    private val names = mapOf(MARY to "Mary", OFFICE to "Dr. Patel's office", BOB to "Bob", ANA to "Ana")

    /** The made-up name for a made-up number, while demo mode is on. */
    fun name(number: String): String? = if (on) names[number] else null

    /** RP Launcher's own phone screens: when it's the phone app, or in demo mode. */
    fun phoneIsOurs(context: Context): Boolean = on || PhoneRole.isDefault(context)

    private fun ago(minutes: Long) = System.currentTimeMillis() - minutes * 60_000L

    val conversations: List<Conversation>
        get() = listOf(
            Conversation(1, listOf(MARY), listOf("Mary"), "Save me a seat by the window", ago(4), unread = 2, hasProblem = false),
            Conversation(2, listOf(OFFICE), listOf("Dr. Patel's office"), "C", ago(90), 0, false),
            Conversation(3, listOf(MARY, BOB, ANA), listOf("Mary", "Bob", "Ana"), "Picture", ago(300), 0, false),
        )

    const val UNREAD = 2

    fun thread(id: Long): ThreadData = when (id) {
        1L -> ThreadData(
            listOf(
                TextMessage(101, false, Box.SENT, ago(50), "Are we still on for dinner tonight?", from = null, delivered = true),
                TextMessage(102, false, Box.INBOX, ago(45), "Yes! 6 o'clock at Mario's", from = MARY),
                TextMessage(103, false, Box.SENT, ago(30), "Great. Bob is giving me a ride", from = null, delivered = true),
                TextMessage(104, false, Box.INBOX, ago(5), "Running a little late, see you at 6", from = MARY, read = false),
                TextMessage(105, false, Box.INBOX, ago(4), "Save me a seat by the window", from = MARY, read = false),
            ),
            listOf(MARY),
        )
        2L -> ThreadData(
            listOf(
                TextMessage(201, false, Box.INBOX, ago(95), "Reminder: your appointment is Tuesday at 10:30 AM. Reply C to confirm.", from = OFFICE),
                TextMessage(202, false, Box.SENT, ago(90), "C", from = null, delivered = true),
            ),
            listOf(OFFICE),
        )
        else -> ThreadData(
            listOf(
                TextMessage(301, false, Box.INBOX, ago(320), "Who's coming to the cookout Saturday?", from = BOB),
                TextMessage(302, false, Box.INBOX, ago(310), "Me! I'll bring the potato salad", from = ANA),
                TextMessage(
                    303, true, Box.INBOX, ago(300), "Here's the flyer", from = MARY,
                    parts = listOf(TextPart(PICTURE_ID, PartKind.IMAGE, "image/png", "flyer.png", "")),
                ),
            ),
            listOf(MARY, BOB, ANA),
        )
    }

    val recents: List<RecentCall>
        get() = listOf(
            RecentCall(MARY, "Mary", RecentCall.Kind.MISSED, ago(20), 0, isNew = true),
            RecentCall(OFFICE, "Dr. Patel's office", RecentCall.Kind.INCOMING, ago(180), 154, isNew = false),
            RecentCall(BOB, "Bob", RecentCall.Kind.OUTGOING, ago(1500), 412, isNew = false),
        )

    const val MISSED = 1

    /** The picture in the group conversation: a flyer with big words, so Read text has something to read. */
    fun picture(maxSide: Int): Bitmap {
        val w = 1200
        val h = 900
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFF7EC8F0.toInt()) // sky
        p.color = 0xFF4CAF50.toInt()
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p) // grass
        p.color = 0xFFFFD54F.toInt()
        c.drawCircle(w * 0.85f, h * 0.17f, 90f, p) // sun
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(w * 0.12f, h * 0.18f, w * 0.72f, h * 0.58f, p) // the sign
        p.color = 0xFF8D6E63.toInt()
        c.drawRect(w * 0.40f, h * 0.58f, w * 0.44f, h * 0.80f, p) // its post
        p.color = 0xFF000000.toInt()
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 96f
        c.drawText("COOKOUT", w * 0.42f, h * 0.31f, p)
        p.textSize = 72f
        c.drawText("Saturday 4 PM", w * 0.42f, h * 0.42f, p)
        c.drawText("Bring a chair", w * 0.42f, h * 0.53f, p)
        if (maxSide >= w) return b
        val scale = maxSide / w.toFloat()
        return Bitmap.createScaledBitmap(b, maxSide, (h * scale).toInt(), true).also { if (it != b) b.recycle() }
    }
}
