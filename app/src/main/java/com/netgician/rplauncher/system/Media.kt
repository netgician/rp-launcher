package com.netgician.rplauncher.system

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The Music tile: Play or Pause, Next and Back for whatever is playing (an audiobook, radio,
 * music or a podcast app), and its volume. What's playing can be named only with Alerts
 * access on; the buttons work either way, as the phone's own media keys.
 */
object Media {
    data class Now(val title: String?, val artist: String?, val app: String?, val playing: Boolean)

    private fun controller(context: Context): MediaController? {
        if (!AlertsStore.hasAccess(context)) return null
        return runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, RpNotificationListener::class.java))
                ?.let { sessions -> sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull() }
        }.getOrNull()
    }

    /** What's playing, when the phone will say. */
    fun now(context: Context): Now? {
        val c = controller(context) ?: return null
        val meta = c.metadata
        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrNull()
        return Now(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
            artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() },
            app = app,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /** True when something is playing right now, by the phone's own account. */
    fun isPlaying(context: Context): Boolean =
        now(context)?.playing ?: (context.getSystemService(AudioManager::class.java)?.isMusicActive == true)

    fun playPause(context: Context) {
        val c = controller(context)
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        } else {
            key(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun next(context: Context) {
        controller(context)?.transportControls?.skipToNext() ?: key(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(context: Context) {
        controller(context)?.transportControls?.skipToPrevious() ?: key(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** Louder or quieter by one step; returns the new volume as a percentage. */
    fun volume(context: Context, up: Boolean): Int {
        val am = context.getSystemService(AudioManager::class.java) ?: return 0
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun key(context: Context, code: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
    }
}
