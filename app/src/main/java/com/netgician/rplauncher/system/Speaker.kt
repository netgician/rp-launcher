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
    /** RP Launcher's own language: the phone's, unless a helper chose another in Setup. */
    private val home: Locale get() = com.netgician.rplauncher.i18n.Lang.locale

    var rate: Float = 1f
    var pitch: Float = 1f

    /** The chosen voice's name, or null for the phone's default voice. */
    var voiceName: String? = null

    /** Installed voices for the phone's own language that work without the internet, best first. */
    fun voices(): List<android.speech.tts.Voice> {
        val engine = tts ?: return emptyList()
        if (!ready) return emptyList()
        return runCatching {
            engine.voices.orEmpty()
                .filter { it.locale.language == home.language && !it.isNetworkConnectionRequired }
                .filter { android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareBy({ it.locale.country != home.country }, { it.name }))
        }.getOrDefault(emptyList())
    }

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
        if (locale == home) {
            voiceName?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } } }
        }
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
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
