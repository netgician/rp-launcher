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

    /** Switches the torch; returns the state it asked for, or null if it couldn't. */
    fun toggle(): Boolean? {
        val id = cameraId ?: return null
        val target = !isOn
        return runCatching { cameras?.setTorchMode(id, target); target }.getOrNull()
    }
}
