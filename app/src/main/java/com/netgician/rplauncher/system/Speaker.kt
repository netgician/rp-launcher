package com.netgician.rplauncher.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import java.util.Locale

/**
 * Speech through the phone's installed voice. Steps aside when TalkBack (or any
 * touch-exploration screen reader) is running, so the person never hears two voices.
 * Spanish text is read with a Spanish voice when the phone has one.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val pending = mutableListOf<Triple<String, String?, Boolean>>()
    private val home: Locale = Locale.getDefault()

    var rate: Float = 1f

    val screenReaderOn: Boolean
        get() = accessibility?.isTouchExplorationEnabled == true

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending.forEach { (text, lang, flush) -> speak(text, lang, flush) }
        pending.clear()
    }

    /**
     * Speaks [text]. [language] is a tag such as "es"; null means the phone's own language.
     * [flush] cuts off whatever is being said; false queues after it.
     */
    fun speak(text: String, language: String? = null, flush: Boolean = true) {
        if (screenReaderOn || text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            if (flush) pending.clear()
            pending += Triple(text, language, flush)
            return
        }
        val locale = language?.let { Locale.forLanguageTag(it) }
            ?.takeIf { it.language != home.language }
            ?.takeIf { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
            ?: home
        runCatching { engine.setLanguage(locale) }
        engine.setSpeechRate(rate)
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "rp-${text.hashCode()}")
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
