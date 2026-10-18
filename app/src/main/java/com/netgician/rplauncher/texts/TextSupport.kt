package com.netgician.rplauncher.texts

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.MainActivity
import com.netgician.rplauncher.R
import com.netgician.rplauncher.mms.Addresses
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Tells the screens that texts changed, and what to say about it. Lives as long as the app does. */
object TextsBus {
    sealed interface Event {
        data class Received(val threadId: Long, val who: String, val preview: String) : Event
        data class Sent(val threadId: Long, val ok: Boolean, val words: String) : Event
        data class DownloadFailed(val threadId: Long, val who: String, val words: String) : Event
    }

    private val _version = MutableStateFlow(0L)
    /** Goes up whenever a text is saved or changes, so lists reload. */
    val version: StateFlow<Long> = _version

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    /** True while RP Launcher is on screen. */
    @Volatile var foreground = false

    /** The conversation on screen right now, so its texts don't also pop up as notifications. */
    @Volatile var openThread: Long? = null

    fun changed() { _version.value = _version.value + 1 }

    fun post(e: Event) { changed(); _events.tryEmit(e) }
}

/** Is RP Launcher the phone's texting app? Only then may it send, receive and store texts. */
object TextRole {
    fun isDefault(context: Context): Boolean =
        runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true }.getOrDefault(false) ||
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    /** The name of the phone's texting app right now, e.g. "Messages", or null if none is set. */
    fun currentAppName(context: Context): String? {
        val pkg = Telephony.Sms.getDefaultSmsPackage(context) ?: return null
        if (pkg == context.packageName) return "RP Launcher"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: when (pkg) {
            "com.google.android.apps.messaging" -> "Google Messages"
            else -> pkg
        }
    }

    /** Opens the phone's own setting for choosing the texting app. */
    fun defaultAppsSettings(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    private val chatApps = linkedMapOf(
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
    )

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** The package of the texting app with chat features (RCS): the current one, else one that's installed. */
    private fun rcsPackage(context: Context): String? {
        val current = Telephony.Sms.getDefaultSmsPackage(context)
        if (current != null && current in chatApps) return current
        return chatApps.keys.firstOrNull { installed(context, it) }
    }

    /** The name of a texting app on this phone that has chat features (RCS), if any. */
    fun rcsApp(context: Context): String? = rcsPackage(context)?.let { chatApps[it] }

    fun previousAppIntent(context: Context): Intent? =
        (rcsPackage(context) ?: Telephony.Sms.getDefaultSmsPackage(context))
            ?.let { context.packageManager.getLaunchIntentForPackage(it) }
}

/**
 * Small saved notes about texts: why one failed, how a download is going, which parts of a
 * long text have gone, numbers learned to be this phone's own. Kept on the phone only.
 */
object SendRecords {
    private fun prefs(c: Context) = c.getSharedPreferences("texts", Context.MODE_PRIVATE)

    fun problem(c: Context, key: String): String? = prefs(c).getString("problem:$key", null)
    fun setProblem(c: Context, key: String, words: String) { prefs(c).edit().putString("problem:$key", words).commit() }
    fun clearProblem(c: Context, key: String) { prefs(c).edit().remove("problem:$key").commit() }

    /** A long text goes as several parts; it counts as sent only when every part has gone. */
    fun startParts(c: Context, smsId: Long, parts: Int) {
        prefs(c).edit().putInt("parts:$smsId", parts).putInt("done:$smsId", 0).putInt("fail:$smsId", 0).commit()
    }

    /** Records one part's result. Returns the result for the whole text once all parts are in, else null. */
    @Synchronized
    fun partResult(c: Context, smsId: Long, resultCode: Int): Int? {
        val p = prefs(c)
        val parts = p.getInt("parts:$smsId", 1)
        val done = p.getInt("done:$smsId", 0) + 1
        var fail = p.getInt("fail:$smsId", 0)
        if (resultCode != Activity.RESULT_OK && fail == 0) fail = if (resultCode == 0) 1 else resultCode
        if (done < parts) {
            p.edit().putInt("done:$smsId", done).putInt("fail:$smsId", fail).commit()
            return null
        }
        p.edit().remove("parts:$smsId").remove("done:$smsId").remove("fail:$smsId").commit()
        return if (fail == 0) Activity.RESULT_OK else fail
    }

    // Downloads: "state|attempt|time|reason"
    fun download(c: Context, mmsId: Long): Pair<DownloadState, String?> {
        val raw = prefs(c).getString("dl:$mmsId", null) ?: return DownloadState.NOT_STARTED to null
        val bits = raw.split('|', limit = 4)
        val state = runCatching { DownloadState.valueOf(bits[0]) }.getOrDefault(DownloadState.NOT_STARTED)
        val time = bits.getOrNull(2)?.toLongOrNull() ?: 0L
        val reason = bits.getOrNull(3)?.takeIf { it.isNotEmpty() }
        if (state == DownloadState.DOWNLOADING && System.currentTimeMillis() - time > Retry.STUCK_MS) {
            return DownloadState.FAILED to MmsDownloads.STOPPED
        }
        return state to reason
    }

    /** How long ago the download record last changed. */
    fun downloadAgeMs(c: Context, mmsId: Long): Long {
        val t = prefs(c).getString("dl:$mmsId", null)?.split('|')?.getOrNull(2)?.toLongOrNull() ?: return 0L
        return System.currentTimeMillis() - t
    }

    fun attempts(c: Context, mmsId: Long): Int =
        prefs(c).getString("dl:$mmsId", null)?.split('|')?.getOrNull(1)?.toIntOrNull() ?: 0

    fun setDownload(c: Context, mmsId: Long, state: DownloadState, attempt: Int, reason: String? = null) {
        prefs(c).edit().putString("dl:$mmsId", "${state.name}|$attempt|${System.currentTimeMillis()}|${reason.orEmpty()}").commit()
    }

    fun clearDownload(c: Context, mmsId: Long) { prefs(c).edit().remove("dl:$mmsId").commit() }

    /** Places picture texts were fetched from, so a repeated "waiting" push isn't fetched twice. */
    fun wasFetched(c: Context, location: String): Boolean =
        prefs(c).getString("fetched", "").orEmpty().split('\n').contains(location)

    fun markFetched(c: Context, location: String) {
        val list = (prefs(c).getString("fetched", "").orEmpty().split('\n').filter { it.isNotEmpty() } + location).takeLast(200)
        prefs(c).edit().putString("fetched", list.joinToString("\n")).commit()
    }

    /**
     * When RP Launcher became the texting app. Only texts after this count as new. Set when
     * switching; on phones that switched before this was recorded, it starts now.
     */
    fun since(c: Context): Long {
        val p = prefs(c)
        val saved = p.getLong("since", 0L)
        if (saved > 0L) return saved
        val now = System.currentTimeMillis()
        p.edit().putLong("since", now).apply()
        return now
    }

    fun setSince(c: Context, time: Long) { prefs(c).edit().putLong("since", time).apply() }

    fun learnedNumbers(c: Context): List<String> =
        prefs(c).getString("mine", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun learnNumber(c: Context, number: String) {
        val now = learnedNumbers(c).filterNot { Addresses.same(it, number) }
        prefs(c).edit().putString("mine", (listOf(number) + now).take(4).joinToString("\n")).commit()
    }
}

/** This phone's own numbers, so they're left out of group conversations. */
object MyNumbers {
    fun get(context: Context): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                sm?.activeSubscriptionInfoList?.forEach { info -> out += sm.getPhoneNumber(info.subscriptionId) }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.line1Number?.let { out += it }
        }
        out += SendRecords.learnedNumbers(context)
        return Addresses.distinct(out.filter { it.isNotBlank() })
    }
}

/**
 * Contact names for numbers. The whole address book is read once (one quick query) rather
 * than looking up each number, so long lists load fast. Without contacts access, numbers are
 * shown nicely spaced.
 */
class Names(private val context: Context) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var book: Map<String, String>? = null

    val canRead: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun of(address: String): String = cache.getOrPut(address) { lookUp(address) }

    /** Forget looked-up names, after contacts access is turned on or contacts change. */
    fun forget() {
        cache.clear()
        book = null
    }

    /** Numbers match on their last ten digits, so +1 919 555 0100 finds (919) 555-0100. */
    private fun key(number: String): String? {
        val d = number.filter { it.isDigit() }
        return when {
            d.length >= 10 -> d.takeLast(10)
            d.length >= 3 -> d
            else -> null
        }
    }

    private fun book(): Map<String, String> = book ?: synchronized(this) {
        book ?: loadBook().also { book = it }
    }

    private fun loadBook(): Map<String, String> {
        if (!canRead) return emptyMap()
        val out = HashMap<String, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    key(c.getString(0).orEmpty())?.let { out.putIfAbsent(it, name) }
                }
            }
        }
        return out
    }

    private fun lookUp(address: String): String {
        if (address.isBlank()) return "Unknown"
        if (address.contains('@')) return address
        key(address)?.let { k -> book()[k]?.let { return it } }
        return PhoneNumberUtils.formatNumber(address, Locale.getDefault().country) ?: address
    }
}

/** The notifications for texts: new ones, and any that didn't send or download. */
object TextNotifications {
    const val CHANNEL_TEXTS = "texts"
    const val CHANNEL_PROBLEMS = "text_problems"
    const val ACTION_OPEN_THREAD = "com.netgician.rplauncher.OPEN_THREAD"
    const val EXTRA_THREAD = "thread"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TEXTS, "Texts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New texts and picture texts"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, "Texts that didn't go through", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A text that wasn't sent, or a picture text that didn't download"
            },
        )
    }

    private fun open(context: Context, threadId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            threadId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_THREAD)
                .putExtra(EXTRA_THREAD, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @Suppress("MissingPermission")
    fun incoming(context: Context, threadId: Long, who: String, words: String) {
        // Skipped only when that very conversation is on screen right now.
        if (!canPost(context) || (TextsBus.foreground && TextsBus.openThread == threadId)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_TEXTS)
            .setSmallIcon(R.drawable.ic_stat_text)
            .setContentTitle(who)
            .setContentText(words)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(context, threadId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag(threadId), 1, n) }
    }

    @Suppress("MissingPermission")
    fun problem(context: Context, threadId: Long, title: String, words: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_text)
            .setContentTitle(title)
            .setContentText(words)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(context, threadId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag(threadId), 2, n) }
    }

    fun cancel(context: Context, threadId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(tag(threadId), 1) }
        runCatching { NotificationManagerCompat.from(context).cancel(tag(threadId), 2) }
    }

    private fun tag(threadId: Long) = "thread:$threadId"
}
