package com.netgician.rplauncher.system

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The torch. Needs no permission: CameraManager's torch mode works without the camera. */
class Torch(context: Context) {
    private val cameras: CameraManager? = context.getSystemService(CameraManager::class.java)

    private val cameraId: String? = runCatching {
        val cm = cameras ?: return@runCatching null
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    val available: Boolean get() = cameraId != null

    var isOn by mutableStateOf(false)
        private set

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) isOn = enabled
        }
    }

    fun start() {
        runCatching { cameras?.registerTorchCallback(callback, Handler(Looper.getMainLooper())) }
    }

    fun stop() {
        runCatching { cameras?.unregisterTorchCallback(callback) }
    }

    /** How many brightness steps the torch has: 1 when it can't be dimmed. */
    val levels: Int = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val id = cameraId ?: return@runCatching 1
            cameras?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } else 1
    }.getOrDefault(1)

    val dimmable: Boolean get() = levels > 1

    /** Switches the torch, at [strength] (0..1) where it can be dimmed; returns the state it asked for, or null. */
    fun toggle(strength: Float = 1f): Boolean? {
        val id = cameraId ?: return null
        val target = !isOn
        return runCatching {
            if (target && dimmable && strength < 1f && android.os.Build.VERSION.SDK_INT >= 33) {
                val level = (levels * strength).toInt().coerceIn(1, levels)
                cameras?.turnOnTorchWithStrengthLevel(id, level)
            } else {
                cameras?.setTorchMode(id, target)
            }
            target
        }.getOrNull()
    }

    /** Changes the brightness while the torch is on. */
    fun setStrength(strength: Float) {
        val id = cameraId ?: return
        if (!isOn || !dimmable || android.os.Build.VERSION.SDK_INT < 33) return
        runCatching { cameras?.turnOnTorchWithStrengthLevel(id, (levels * strength).toInt().coerceIn(1, levels)) }
    }
}
