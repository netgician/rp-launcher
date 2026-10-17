package com.netgician.rplauncher.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import java.util.Locale

/**
 * Speech through the phone's installed voice. Steps aside when TalkBack (or any
 * touch-exploration screen reader) is running, so the person never hears two voices.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    var rate: Float = 1f

    val screenReaderOn: Boolean
        get() = accessibility?.isTouchExplorationEnabled == true

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { tts?.language = Locale.getDefault() }
            pending?.let { speak(it) }
        }
        pending = null
    }

    fun speak(text: String) {
        if (screenReaderOn || text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rp-launcher")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
