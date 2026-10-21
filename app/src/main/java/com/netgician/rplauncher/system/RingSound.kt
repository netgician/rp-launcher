package com.netgician.rplauncher.system

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The phone's alarm sound, looping at alarm volume (heard with the ringer on silent), and a
 * repeating buzz. Played by the ring screens while they show, so no notification banner has
 * to carry the sound and cover the screen.
 */
class RingSound(private val context: Context) {
    private var sound: Ringtone? = null
    private var buzzing = false

    private val alarmAudio: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun start() {
        if (sound == null) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            sound = uri?.let {
                runCatching {
                    RingtoneManager.getRingtone(context, it)?.apply {
                        audioAttributes = alarmAudio
                        if (Build.VERSION.SDK_INT >= 28) isLooping = true
                        play()
                    }
                }.getOrNull()
            }
        }
        if (!buzzing) {
            buzzing = runCatching {
                @Suppress("DEPRECATION")
                vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 1_200), 0), alarmAudio)
            }.isSuccess
        }
    }

    fun stop() {
        runCatching { sound?.stop() }
        sound = null
        if (buzzing) runCatching { vibrator()?.cancel() }
        buzzing = false
    }
}
