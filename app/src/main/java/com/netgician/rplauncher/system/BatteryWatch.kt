package com.netgician.rplauncher.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.R
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.BatteryRules

/**
 * Low battery: said aloud, with a buzz and a notification, at 20, 10 and 5 percent, once each
 * time the battery runs down. Watched while RP Launcher runs, which as the home screen is
 * nearly always. Setup can turn it off.
 */
object BatteryWatch : BroadcastReceiver() {
    private const val CHANNEL = "battery"
    private const val NOTIFICATION_ID = 7201
    private var speaker: Speaker? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, this, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
        val last = prefs.getInt("warned", 100)
        val (warn, next) = BatteryRules.check(percent, charging, last)
        if (next != last) prefs.edit().putInt("warned", next).apply()
        if (warn == null) return
        if (!runCatching { ConfigStore(context).load().lowBatteryWarning }.getOrDefault(true)) return
        warn(context.applicationContext, warn)
    }

    private fun warn(context: Context, percent: Int) {
        val words = tr("Battery is at %s percent. Please charge your phone.", percent)
        Vibes(context).play(longArrayOf(0, 300, 200, 300))
        val s = speaker ?: Speaker(context).also { speaker = it }
        runCatching { s.rate = ConfigStore(context).load().speechRate }
        s.speak(words)
        if (!Timer.notificationsAllowed(context)) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Low battery"), NotificationManager.IMPORTANCE_HIGH),
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(tr("Battery %s%%", percent))
            .setContentText(tr("Please charge your phone."))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}
