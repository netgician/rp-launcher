package com.netgician.rplauncher.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.R
import com.netgician.rplauncher.texts.Names
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The calls going on right now, for the call screen. Filled by [RpInCallService] while
 * RP Launcher is the phone app.
 */
object Calls {
    private val _list = MutableStateFlow<List<CallInfo>>(emptyList())
    val list: StateFlow<List<CallInfo>> = _list

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio

    internal val calls = mutableListOf<Call>()
    private val ids = java.util.WeakHashMap<Call, String>()
    private var nextId = 1

    internal fun idOf(c: Call): String = synchronized(ids) { ids.getOrPut(c) { "call-${nextId++}" } }
    @Volatile internal var service: RpInCallService? = null

    internal fun refresh(context: Context) {
        val names = Names(context)
        _list.value = calls.toList().map { info(it, names) }
    }

    internal fun setAudio(state: CallAudioState?) { _audio.value = state }

    private fun info(c: Call, names: Names): CallInfo {
        val number = c.details?.handle?.schemeSpecificPart.orEmpty()
        val shownName = c.details?.let { d ->
            (if (Build.VERSION.SDK_INT >= 30) d.contactDisplayName else null)?.takeIf { it.isNotBlank() }
                ?: d.callerDisplayName?.takeIf { it.isNotBlank() }
        } ?: if (number.isBlank()) "Private number" else names.of(number)
        val state = if (Build.VERSION.SDK_INT >= 31) c.details.state else @Suppress("DEPRECATION") c.state
        val phase = when (state) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_DIALING -> CallPhase.DIALING
            Call.STATE_CONNECTING, Call.STATE_PULLING_CALL, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> CallPhase.CONNECTING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallPhase.ENDED
            else -> CallPhase.OTHER
        }
        val connected = c.details?.connectTimeMillis?.takeIf { it > 0 } ?: 0L
        return CallInfo(
            id = idOf(c),
            number = number,
            name = shownName,
            phase = phase,
            connectedAt = connected,
            conference = c.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true,
        )
    }

    private fun byId(id: String): Call? = calls.firstOrNull { idOf(it) == id }

    // ---- what the call screen can do ----

    fun answer(id: String) { byId(id)?.answer(VideoProfile.STATE_AUDIO_ONLY) }

    fun decline(id: String) { byId(id)?.reject(false, null) }

    /** Declines and texts back, through the phone's texting app. */
    fun declineWithText(id: String, text: String) { byId(id)?.reject(true, text) }

    fun hangUp(id: String) { byId(id)?.disconnect() }

    fun hold(id: String, on: Boolean) { byId(id)?.let { if (on) it.hold() else it.unhold() } }

    fun key(id: String, digit: Char) {
        byId(id)?.let {
            it.playDtmfTone(digit)
            it.stopDtmfTone()
        }
    }

    fun mute(on: Boolean) { service?.setMuted(on) }

    /** Speaker on, or back to the earpiece (or a headset or hearing aid, if one is connected). */
    @Suppress("DEPRECATION")
    fun speaker(on: Boolean) {
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    val speakerOn: Boolean get() = _audio.value?.route == CallAudioState.ROUTE_SPEAKER
    val muted: Boolean get() = _audio.value?.isMuted == true
}

/**
 * The phone's call screen while RP Launcher is the phone app. The phone keeps ringing as
 * usual; this shows who's calling, large, and big Answer, Decline and Hang up buttons.
 * If it ever fails, Android falls back to the phone's own call screen.
 */
class RpInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Calls.refresh(this@RpInCallService)
            updateNotification()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            Calls.refresh(this@RpInCallService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Calls.service = this
    }

    override fun onDestroy() {
        if (Calls.service === this) Calls.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        Calls.calls += call
        call.registerCallback(callback)
        Calls.refresh(this)
        updateNotification()
        openScreen()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        Calls.calls -= call
        Calls.refresh(this)
        updateNotification()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        Calls.setAudio(audioState)
    }

    private fun openScreen() {
        runCatching {
            startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun screenIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One notification: "Call from Mary" while ringing (it opens the call screen over the lock screen), then "On a call". */
    private fun updateNotification() {
        val nm = NotificationManagerCompat.from(this)
        val list = Calls.list.value
        if (list.isEmpty() || list.all { it.phase == CallPhase.ENDED }) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channels(this)
        val ringing = list.firstOrNull { it.phase == CallPhase.RINGING }
        val n = if (ringing != null) {
            NotificationCompat.Builder(this, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle("Call from ${ringing.name}")
                .setContentText("Tap to answer")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(screenIntent(), true)
                .setContentIntent(screenIntent())
                .build()
        } else {
            val c = list.first()
            NotificationCompat.Builder(this, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle("On a call with ${c.name}")
                .setContentText("Tap to return to the call")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setUsesChronometer(c.connectedAt > 0)
                .setWhen(if (c.connectedAt > 0) c.connectedAt else System.currentTimeMillis())
                .setContentIntent(screenIntent())
                .build()
        }
        @Suppress("MissingPermission")
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        private const val CHANNEL_INCOMING = "calls_incoming"
        private const val CHANNEL_ONGOING = "calls_ongoing"

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    // The phone itself plays the ringtone; this one stays quiet so it doesn't ring twice.
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

/** Is RP Launcher the phone app, and how to make it so or go back. */
object PhoneRole {
    fun isDefault(context: Context): Boolean =
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName

    fun currentAppName(context: Context): String? {
        val pkg = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage ?: return null
        if (pkg == context.packageName) return "RP Launcher"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Places a call through the phone. Emergency numbers work as on any phone. */
    @android.annotation.SuppressLint("MissingPermission")
    fun call(context: Context, number: String): Boolean {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return false
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return false
        return runCatching { tm.placeCall(Uri.fromParts("tel", number, null), null); true }.getOrDefault(false)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun voicemail(context: Context): Boolean {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return false
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return false
        return runCatching { tm.placeCall(Uri.fromParts("voicemail", "", null), null); true }.getOrDefault(false)
    }

    fun defaultAppsSettings(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
