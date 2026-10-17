package com.netgician.rplauncher.system

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays vibration patterns (touch landmarks). Silent on phones without a vibrator. */
class Vibes(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun play(timings: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || timings.size < 2) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, -1)) }
    }
}

/** The room's light level, for automatic night colours. Listens only while the launcher is visible. */
class LightSensor(context: Context, private val onLux: (Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean get() = light != null

    fun start() {
        val s = light ?: return
        sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        event.values.firstOrNull()?.let(onLux)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * The phone's own screen lock, used to reset a forgotten helper PIN. Whoever can unlock
 * the phone can reset the PIN; the PIN only guards against accidental changes.
 */
object ScreenLock {
    fun isSet(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun confirm(context: Context, onSuccess: () -> Unit, onFailure: () -> Unit) {
        val builder = BiometricPrompt.Builder(context)
            .setTitle("Reset the helper PIN")
            .setSubtitle("Unlock with this phone's screen lock")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val prompt = runCatching { builder.build() }.getOrElse {
            onFailure()
            return
        }
        prompt.authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            },
        )
    }
}
