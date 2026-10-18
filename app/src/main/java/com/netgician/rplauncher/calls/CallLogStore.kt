package com.netgician.rplauncher.calls

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.MainActivity

/** Recent calls from the phone's call log. Needs call log access, which the phone app gets. */
object CallLogStore {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun recent(context: Context, limit: Int = 60): List<RecentCall> {
        if (!allowed(context)) return emptyList()
        val out = mutableListOf<RecentCall>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.NEW),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val kind = when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> RecentCall.Kind.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> RecentCall.Kind.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> RecentCall.Kind.MISSED
                        CallLog.Calls.REJECTED_TYPE -> RecentCall.Kind.DECLINED
                        CallLog.Calls.BLOCKED_TYPE -> RecentCall.Kind.BLOCKED
                        CallLog.Calls.VOICEMAIL_TYPE -> RecentCall.Kind.VOICEMAIL
                        else -> RecentCall.Kind.INCOMING
                    }
                    out += RecentCall(
                        number = c.getString(0).orEmpty(),
                        name = c.getString(1)?.takeIf { it.isNotBlank() },
                        kind = kind,
                        date = c.getLong(3),
                        durationSeconds = c.getLong(4),
                        isNew = c.getInt(5) != 0 && kind == RecentCall.Kind.MISSED,
                    )
                }
            }
        }
        return out
    }

    fun missedCount(context: Context): Int {
        if (!allowed(context)) return 0
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND ${CallLog.Calls.NEW} = 1", null, null,
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    /** Missed calls have been seen: clears the count here and the phone's missed-call notification. */
    @android.annotation.SuppressLint("MissingPermission")
    fun markSeen(context: Context) {
        runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                val v = ContentValues().apply { put(CallLog.Calls.NEW, 0); put(CallLog.Calls.IS_READ, 1) }
                context.contentResolver.update(CallLog.Calls.CONTENT_URI, v, "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}", null)
            }
        }
        runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.MODIFY_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
                PhoneRole.isDefault(context)
            ) context.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        }
    }
}

/**
 * "Call this number" and "open the phone" from other apps. Opens RP Launcher's keypad with
 * the number filled in, or hands it to the phone's own dialer when RP Launcher isn't the phone app.
 */
class DialActivity : ComponentActivity() {
    companion object {
        const val ACTION_DIAL_NUMBER = "com.netgician.rplauncher.DIAL_NUMBER"
        const val EXTRA_NUMBER = "number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (!PhoneRole.isDefault(this)) {
            val other = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            if (other != null && i != null) runCatching { startActivity(Intent(i).setComponent(null).setPackage(other)) }
            finish()
            return
        }
        val number = i?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.let { Uri.decode(it) }.orEmpty()
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_DIAL_NUMBER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NUMBER, number),
        )
        finish()
    }
}
