package com.netgician.rplauncher.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG relative luminance and contrast ratio, computed rather than eyeballed. */
object Contrast {

    private fun channel(value: Int): Double {
        val s = value / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance of an ARGB colour such as 0xFF000000. Alpha is ignored. */
    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
