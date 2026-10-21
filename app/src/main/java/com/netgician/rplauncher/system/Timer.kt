package com.netgician.rplauncher.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.R
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.model.TimerState
import com.netgician.rplauncher.model.TimerWords
import com.netgician.rplauncher.ui.TimerRingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Timer tile's timer: kept on the phone so it survives RP Launcher closing, rung by an
 * alarm so it goes off on time with the screen locked, and loud: the alarm sound and a
 * vibration, repeating, with a full-screen Stop over the lock screen.
 */
object Timer {
    private const val PREFS = "timer"
    private const val NOTIFICATION_ID = 7101
    private const val CHANNEL = "timer_ring"
    const val ACTION_RING = "com.netgician.rplauncher.TIMER_RING"
    const val ACTION_STOP = "com.netgician.rplauncher.TIMER_STOP"
    const val ACTION_MORE = "com.netgician.rplauncher.TIMER_MORE"

    private val current = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = current
    @Volatile private var loaded = false

    private fun now() = System.currentTimeMillis()

    @Synchronized
    fun load(context: Context): TimerState {
        if (!loaded) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            current.value = TimerState(
                phase = runCatching { TimerState.Phase.valueOf(p.getString("phase", null) ?: "IDLE") }.getOrDefault(TimerState.Phase.IDLE),
                endsAt = p.getLong("endsAt", 0L),
                leftMs = p.getLong("leftMs", 0L),
                setMs = p.getLong("setMs", 0L),
                rangAt = p.getLong("rangAt", 0L),
            )
            loaded = true
        }
        return current.value
    }

    @Synchronized
    private fun save(context: Context, s: TimerState) {
        current.value = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phase", s.phase.name)
            .putLong("endsAt", s.endsAt)
            .putLong("leftMs", s.leftMs)
            .putLong("setMs", s.setMs)
            .putLong("rangAt", s.rangAt)
            .apply()
    }

    fun start(context: Context, ms: Long) {
        quiet(context)
        val s = load(context).start(now(), ms)
        save(context, s)
        schedule(context, s)
    }

    fun pause(context: Context) {
        save(context, load(context).pause(now()))
        unschedule(context)
    }

    fun resume(context: Context) {
        val s = load(context).resume(now())
        save(context, s)
        schedule(context, s)
    }

    fun addMinute(context: Context) {
        quiet(context)
        val s = load(context).addMinute(now())
        save(context, s)
        if (s.phase == TimerState.Phase.RUNNING) schedule(context, s)
    }

    /** Stop timer: throws away a running or paused timer. */
    fun cancel(context: Context) {
        save(context, TimerState())
        unschedule(context)
        quiet(context)
    }

    /** Stop on the ringing screen or in the notification. */
    fun stop(context: Context) = cancel(context)

    /** The alarm went off: rings if the time has come, or sets the alarm again if it was early. */
    fun ringIfDue(context: Context) {
        val s = load(context)
        if (s.isDue(now())) ring(context)
        else if (s.phase == TimerState.Phase.RUNNING) schedule(context, s)
    }

    /** Every second while RP Launcher is on screen, so it rings on time even without the alarm. */
    fun tick(context: Context) {
        if (load(context).isDue(now())) ring(context)
    }

    private fun ring(context: Context) {
        val s = load(context)
        if (s.phase == TimerState.Phase.RINGING) return
        save(context, s.ring(now()))
        unschedule(context)
        notifyRinging(context)
        // Shown at once when RP Launcher is on screen; otherwise the notification brings it up
        // over the lock screen, or as a banner with Stop while another app is open.
        runCatching {
            context.startActivity(Intent(context, TimerRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** After a restart: picks the timer up again, rings one that only just ran out, clears old ones. */
    fun restore(context: Context) {
        val before = load(context)
        val after = TimerRules.restored(before, now())
        when {
            after.phase == TimerState.Phase.RINGING && before.phase == TimerState.Phase.RUNNING -> ring(context)
            after != before -> save(context, after)
        }
        if (after.phase == TimerState.Phase.RUNNING) schedule(context, after)
    }

    /** False when Android may ring it late while the phone sleeps (Alarms and reminders not allowed). */
    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Android's page for "Alarms and reminders", where a helper allows it. */
    fun exactSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TimerReceiver::class.java).setAction(ACTION_RING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // canBeExact() is checked first; without it the alarm is set inexact instead.
    @android.annotation.SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun schedule(context: Context, s: TimerState) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        val exact = canBeExact(context) && runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endsAt, pi)
        }.isSuccess
        if (!exact) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endsAt, pi) }
    }

    private fun unschedule(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context)) }
    }

    /** Silences the ringing: the notification carries the sound. */
    private fun quiet(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Timer"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("The Timer tile's alarm")
                // The alarm sound, at alarm volume, so it's heard with the ringer on silent.
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
        )
    }

    private fun notifyRinging(context: Context) {
        if (!notificationsAllowed(context)) return
        channel(context)
        val screen = PendingIntent.getActivity(
            context, 1,
            Intent(context, TimerRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(code: Int, what: String) = PendingIntent.getBroadcast(
            context, code, Intent(context, TimerReceiver::class.java).setAction(what),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = load(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(tr("Timer done"))
            .setContentText(TimerWords.which(s).ifEmpty { tr("Tap Stop to turn it off.") })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(0, tr("Stop"), action(2, ACTION_STOP))
            .addAction(0, tr("1 more minute"), action(3, ACTION_MORE))
            .setTimeoutAfter(TimerRules.RING_FOR_MS)
            .build()
        // The sound and vibration repeat until Stop.
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}

/** The timer's alarm, and Stop and 1 more minute from its notification. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Timer.ACTION_RING -> Timer.ringIfDue(context)
            Timer.ACTION_STOP -> Timer.stop(context)
            Timer.ACTION_MORE -> Timer.addMinute(context)
        }
    }
}
