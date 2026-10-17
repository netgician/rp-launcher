package com.netgician.rplauncher.system

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.netgician.rplauncher.model.AlertItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * New notifications for the reading viewer. Filled by [RpNotificationListener] once the
 * person grants notification access; kept only in memory, never saved or sent anywhere.
 */
object AlertsStore {
    private val _items = MutableStateFlow<List<AlertItem>>(emptyList())
    val items: StateFlow<List<AlertItem>> = _items

    private var intents: Map<String, PendingIntent> = emptyMap()

    @Volatile
    internal var service: RpNotificationListener? = null

    internal fun set(list: List<AlertItem>, openers: Map<String, PendingIntent>) {
        intents = openers
        _items.value = list
    }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** The settings page where the person turns on notification access for RP Launcher. */
    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, RpNotificationListener::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    /** App info, where Android 13 and later hide "Allow restricted settings" for sideloaded apps. */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Opens what the notification points to. Returns false if it can't be opened. */
    fun open(context: Context, key: String): Boolean {
        val pi = intents[key] ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pi.send(context, 0, null, null, null, null, options)
            } else {
                pi.send()
            }
        }.isSuccess
    }

    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
        _items.value = _items.value.filterNot { it.key == key }
    }
}

/** Reads new notifications so they can be shown large and spoken. */
class RpNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        AlertsStore.service = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (AlertsStore.service === this) AlertsStore.service = null
        AlertsStore.set(emptyList(), emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val items = mutableListOf<AlertItem>()
        val openers = mutableMapOf<String, PendingIntent>()
        for (sbn in active.sortedByDescending { it.postTime }) {
            if (sbn.packageName == packageName || sbn.isOngoing) continue
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
            if (title.isBlank() && text.isBlank()) continue
            items += AlertItem(sbn.key, appLabel(sbn.packageName), title, text, sbn.postTime)
            n.contentIntent?.let { openers[sbn.key] = it }
        }
        AlertsStore.set(items, openers)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
