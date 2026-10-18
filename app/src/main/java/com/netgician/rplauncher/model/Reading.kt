package com.netgician.rplauncher.model

import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.ocr.TextBox

/** Picks the voice for a piece of text: Spanish or English. */
object Language {
    const val ENGLISH = "en"
    const val SPANISH = "es"

    private val spanishWords = setOf(
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "por", "con", "para",
        "es", "su", "al", "lo", "se", "no", "más", "cada", "antes", "después", "usted", "llame",
    )
    private val englishWords = setOf(
        "the", "and", "of", "to", "in", "is", "for", "with", "on", "your", "you", "at", "be",
        "do", "not", "every", "take", "call", "from", "this", "that", "are", "it", "or",
    )

    /** Spanish when Spanish letters and common Spanish words outweigh English ones. */
    fun guess(text: String): String {
        val lower = text.lowercase()
        var es = lower.count { it in "áéíóúñü¿¡" } * 2
        var en = 0
        for (w in lower.split(Regex("[^\\p{L}]+"))) {
            if (w in spanishWords) es++
            if (w in englishWords) en++
        }
        return if (es > en) SPANISH else ENGLISH
    }
}

/** Spoken advice after reading, so the person can frame a better shot next time. */
object AimHelp {
    /** Fraction of the picture counted as "at the edge". */
    const val EDGE = 0.015f

    fun hints(boxes: List<TextBox>, width: Int, height: Int): List<String> {
        if (boxes.isEmpty()) {
            return listOf(tr("No text found. Hold the phone a little farther away, and add light if you can."))
        }
        val mx = width * EDGE
        val my = height * EDGE
        val sides = buildList {
            if (boxes.any { it.left <= mx }) add(tr("left"))
            if (boxes.any { it.right >= width - mx }) add(tr("right"))
            if (boxes.any { it.top <= my }) add(tr("top"))
            if (boxes.any { it.bottom >= height - my }) add(tr("bottom"))
        }
        return if (sides.isEmpty()) emptyList()
        else listOf(tr("Some text may be cut off at the %s. Move the phone back a little.", sides.joinToString(tr(" and "))))
    }
}

/** One notification, as the reading viewer shows and speaks it. */
data class AlertItem(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
) {
    val shown: String get() = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
    val speech: String get() = listOf(tr("From %s", app), title, text).filter { it.isNotBlank() }.joinToString(". ")
}
