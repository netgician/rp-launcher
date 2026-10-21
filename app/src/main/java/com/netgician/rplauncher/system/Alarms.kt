package com.netgician.rplauncher.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.netgician.rplauncher.MainActivity
import com.netgician.rplauncher.R
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.Alarm
import com.netgician.rplauncher.model.AlarmRules
import com.netgician.rplauncher.model.AlarmWords
import com.netgician.rplauncher.ui.AlarmRingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * The Alarms and Reminders tiles: kept on the phone, each set as an alarm clock with Android
 * so it rings on time with the phone locked and asleep, and rung loudly with a full-screen
 * Stop (or Done) over the lock screen, like the timer.
 */
object Alarms {
    private const val PREFS = "alarms"
    private const val CHANNEL = "alarm_ring"
    private const val CHANNEL_QUIET = "alarm_quiet"
    const val ACTION_RING = "com.netgician.rplauncher.ALARM_RING"
    const val ACTION_STOP = "com.netgician.rplauncher.ALARM_STOP"
    const val ACTION_SNOOZE = "com.netgician.rplauncher.ALARM_SNOOZE"
    const val EXTRA_ID = "id"

    private val all = MutableStateFlow<List<Alarm>>(emptyList())
    val list: StateFlow<List<Alarm>> = all

    /** What's going off right now, oldest first, with when it started. */
    private val ringingNow = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val ringing: StateFlow<List<Pair<Long, Long>>> = ringingNow

    @Volatile private var loaded = false

    private fun now() = System.currentTimeMillis()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Synchronized
    fun load(context: Context): List<Alarm> {
        if (!loaded) {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null)
            all.value = runCatching { decode(text ?: "[]") }.getOrDefault(emptyList())
            loaded = true
        }
        return all.value
    }

    fun get(context: Context, id: Long): Alarm? = load(context).firstOrNull { it.id == id }

    @Synchronized
    private fun save(context: Context, list: List<Alarm>) {
        all.value = list.sortedWith(compareBy({ it.hour }, { it.minute }))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", encode(all.value)).apply()
    }

    /** Adds or replaces one, sets it with Android, and returns when it next rings. */
    fun put(context: Context, a: Alarm): Long? {
        save(context, load(context).filterNot { it.id == a.id } + a)
        schedule(context, a)
        return AlarmRules.next(a, now(), zone)
    }

    fun delete(context: Context, id: Long) {
        unschedule(context, id)
        stopRinging(context, id)
        save(context, load(context).filterNot { it.id == id })
    }

    fun newId(): Long = now()

    /** Sets every alarm with Android again: at start-up, after the phone restarts, or the clock changes. */
    fun restore(context: Context) {
        load(context).forEach { schedule(context, it) }
        ringingNow.value = ringingNow.value.filter { (_, since) -> now() - since < AlarmRules.RING_FOR_MS }
    }

    /** Android's alarm went off for [id]. */
    fun ring(context: Context, id: Long) {
        val a = get(context, id) ?: return
        if (ringingNow.value.any { it.first == id }) return
        // A one-time alarm turns off; a repeating one is set for its next day.
        val after = AlarmRules.afterRinging(a)
        save(context, load(context).map { if (it.id == id) after else it })
        schedule(context, after)
        ringingNow.value = ringingNow.value + (id to now())
        notify(context, a, loud = !Timer.appVisible)
        runCatching {
            context.startActivity(Intent(context, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Stop (alarm) or Done (reminder). */
    fun stopRinging(context: Context, id: Long) {
        ringingNow.value = ringingNow.value.filterNot { it.first == id }
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(id)) }
    }

    /** 10 more minutes. */
    fun snooze(context: Context, id: Long) {
        stopRinging(context, id)
        val a = get(context, id) ?: return
        put(context, AlarmRules.snoozed(a, now()))
    }

    /** The ring screen is showing and plays the sound itself: the notifications go quiet. */
    fun takeOver(context: Context) {
        ringingNow.value.forEach { (id, _) -> get(context, id)?.let { notify(context, it, loud = false) } }
    }

    /** The next alarm (not reminder) that's on, for the Alarms tile. */
    fun nextAlarm(context: Context): Pair<Alarm, Long>? =
        load(context).filter { !it.isReminder }.mapNotNull { a -> AlarmRules.next(a, now(), zone)?.let { a to it } }.minByOrNull { it.second }

    private fun requestCode(id: Long): Int = (id % 1_000_000_000L).toInt()
    private fun notificationId(id: Long): Int = 8_000 + (id % 100_000L).toInt()

    private fun ringIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(id),
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @android.annotation.SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun schedule(context: Context, a: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = ringIntent(context, a.id)
        val at = AlarmRules.next(a, now(), zone)
        if (at == null) {
            runCatching { am.cancel(pi) }
            return
        }
        // An alarm clock: the surest kind, shown as the next alarm on the lock screen.
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val set = Timer.canBeExact(context) && runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi) }.isSuccess
        if (!set) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun unschedule(context: Context, id: Long) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(ringIntent(context, id)) }
    }

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Alarms and reminders"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, tr("Alarm ringing"), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
        )
    }

    private fun notify(context: Context, a: Alarm, loud: Boolean) {
        if (!Timer.notificationsAllowed(context)) return
        channels(context)
        val screen = PendingIntent.getActivity(
            context, 2,
            Intent(context, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(what: String) = PendingIntent.getBroadcast(
            context, requestCode(a.id) xor what.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(what).putExtra(EXTRA_ID, a.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val is24 = DateFormat.is24HourFormat(context)
        val time = AlarmWords.time(a.hour, a.minute, is24, Lang.locale)
        val n = NotificationCompat.Builder(context, if (loud) CHANNEL else CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (a.isReminder) a.message else tr("Alarm %s", time))
            .setContentText(if (a.isReminder) tr("Reminder, %s", time) else tr("Tap Stop to turn it off."))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .addAction(0, if (a.isReminder) tr("Done") else tr("Stop"), action(ACTION_STOP))
            .addAction(0, tr("10 more minutes"), action(ACTION_SNOOZE))
            .setTimeoutAfter(AlarmRules.RING_FOR_MS)
            .apply { if (loud) setFullScreenIntent(screen, true) }
            .build()
        if (loud) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(a.id), n) }
    }

    fun encode(list: List<Alarm>): String = JSONArray().apply {
        list.forEach { a ->
            put(
                JSONObject()
                    .put("id", a.id).put("hour", a.hour).put("minute", a.minute)
                    .put("days", JSONArray(a.days.sorted()))
                    .put("message", a.message ?: JSONObject.NULL)
                    .put("on", a.on).put("snoozedUntil", a.snoozedUntil),
            )
        }
    }.toString()

    fun decode(text: String): List<Alarm> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val days = o.optJSONArray("days")
            Alarm(
                id = o.optLong("id"),
                hour = o.optInt("hour").coerceIn(0, 23),
                minute = o.optInt("minute").coerceIn(0, 59),
                days = (0 until (days?.length() ?: 0)).map { days!!.optInt(it) }.filter { it in 1..7 }.toSet(),
                message = if (o.isNull("message")) null else o.optString("message").ifBlank { null },
                on = o.optBoolean("on", true),
                snoozedUntil = o.optLong("snoozedUntil", 0L),
            )
        }
    }
}

/** An alarm or reminder going off, and Stop, Done and 10 more minutes from its notification. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_ID, -1L)
        when (intent.action) {
            Alarms.ACTION_RING -> Alarms.ring(context, id)
            Alarms.ACTION_STOP -> Alarms.stopRinging(context, id)
            Alarms.ACTION_SNOOZE -> Alarms.snooze(context, id)
        }
    }
}

/** The phone's clock or time zone changed: every alarm and the timer are set again for the new time. */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                Alarms.restore(context)
                Timer.restore(context)
            }
        }
    }
}
