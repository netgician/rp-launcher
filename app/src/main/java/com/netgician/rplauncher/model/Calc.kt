package com.netgician.rplauncher.model

import com.netgician.rplauncher.i18n.tr
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The big calculator: a plain four-function calculator, worked out exactly (no 0.1 + 0.2
 * surprises). [shown] is the number on screen; [pending] is the first number and the sign
 * waiting for the second ("12 +").
 */
data class Calc(
    val shown: String = "0",
    val stored: BigDecimal? = null,
    val op: Char? = null,
    /** True after a sign or equals: the next digit starts a new number. */
    val fresh: Boolean = true,
    val error: String? = null,
) {
    val pending: String get() = if (stored != null && op != null) "${CalcRules.format(stored)} $op" else ""
}

object CalcRules {
    const val PLUS = '+'
    const val MINUS = '−'
    const val TIMES = '×'
    const val DIVIDE = '÷'
    private const val MAX_DIGITS = 12

    fun digit(c: Calc, d: Char): Calc {
        if (c.error != null) return digit(Calc(), d)
        if (c.fresh || c.shown == "0") return c.copy(shown = d.toString(), fresh = false)
        if (c.shown.count { it.isDigit() } >= MAX_DIGITS) return c
        return c.copy(shown = c.shown + d)
    }

    fun point(c: Calc): Calc {
        if (c.error != null) return point(Calc())
        if (c.fresh) return c.copy(shown = "0.", fresh = false)
        if ('.' in c.shown) return c
        return c.copy(shown = c.shown + ".")
    }

    fun delete(c: Calc): Calc {
        if (c.error != null || c.fresh) return c
        val left = c.shown.dropLast(1)
        return c.copy(shown = if (left.isEmpty() || left == "-") "0" else left)
    }

    fun clear(): Calc = Calc()

    /** A sign: works out anything already waiting first, so 2 + 3 × reads 5 ×. */
    fun op(c: Calc, o: Char): Calc {
        if (c.error != null) return c
        val now = value(c)
        if (c.op != null && c.stored != null && !c.fresh) {
            val r = compute(c.stored, c.op, now) ?: return Calc(error = tr("Can't divide by zero"))
            return Calc(shown = format(r), stored = r, op = o, fresh = true)
        }
        return c.copy(stored = if (c.fresh && c.stored != null) c.stored else now, op = o, fresh = true)
    }

    fun equals(c: Calc): Calc {
        if (c.error != null || c.op == null || c.stored == null) return c.copy(fresh = true)
        val r = compute(c.stored, c.op, value(c)) ?: return Calc(error = tr("Can't divide by zero"))
        return Calc(shown = format(r), fresh = true)
    }

    fun value(c: Calc): BigDecimal = c.shown.trimEnd('.').toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** Null when dividing by zero. */
    fun compute(a: BigDecimal, o: Char, b: BigDecimal): BigDecimal? = when (o) {
        PLUS -> a + b
        MINUS -> a - b
        TIMES -> a * b
        DIVIDE -> if (b.signum() == 0) null else a.divide(b, MathContext(16, RoundingMode.HALF_UP))
        else -> b
    }

    /** "12", "0.5", "3.333333333": no trailing zeros, at most 12 significant digits. */
    fun format(x: BigDecimal): String {
        val r = x.round(MathContext(MAX_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = r.toPlainString()
        return if (plain.length <= MAX_DIGITS + 3) plain else r.toString()
    }

    /** What a key says when it's pressed. */
    fun keyName(k: Char): String = when (k) {
        PLUS -> tr("plus")
        MINUS -> tr("minus")
        TIMES -> tr("times")
        DIVIDE -> tr("divided by")
        '.' -> tr("point")
        else -> k.toString()
    }

    /** A number for the voice: "minus 5" rather than a dash. */
    fun spoken(number: String): String = if (number.startsWith("-")) tr("minus %s", number.drop(1)) else number

    /** The whole screen for the voice: "12 plus 3", or the answer. */
    fun spokenScreen(c: Calc): String = when {
        c.error != null -> c.error
        c.op != null && c.stored != null && !c.fresh -> "${spoken(format(c.stored))} ${keyName(c.op)} ${spoken(c.shown)}"
        c.op != null && c.stored != null -> "${spoken(format(c.stored))} ${keyName(c.op)}"
        else -> spoken(c.shown)
    }
}

/** When to warn about a low battery. */
object BatteryRules {
    val LEVELS: List<Int> = listOf(20, 10, 5)

    /** Charging, or charged back above this, starts the warnings afresh. */
    const val RESET_ABOVE = 25

    /**
     * Given the battery now and the last level warned about, returns the level to warn about
     * now (or null) and the level to remember. Each level is said once per run-down.
     */
    fun check(percent: Int, charging: Boolean, lastWarned: Int): Pair<Int?, Int> {
        if (charging || percent > RESET_ABOVE) return null to 100
        val due = LEVELS.filter { percent <= it && it < lastWarned }.minOrNull() ?: return null to lastWarned
        return due to due
    }
}

/** The light finder: how bright the camera's view really is, as a word and a tone. */
object LightRules {
    /**
     * 0 (dark) to 1 (very bright) from the picture's average brightness (0 to 255) and the
     * camera's exposure, since the camera brightens dim scenes itself. A dark room, a lit room,
     * a lamp up close and a sunny window fall roughly at 0, 0.45, 0.9 and 1.
     */
    fun level(meanLuma: Double, exposureNs: Long, iso: Int): Float {
        if (exposureNs <= 0 || iso <= 0) return (meanLuma / 255.0).toFloat().coerceIn(0f, 1f)
        val seconds = exposureNs / 1e9
        val light = (meanLuma + 1.0) / (seconds * iso / 100.0)
        // Roughly: dark room 1, lit room 3, a lamp up close 5, a sunny window 5.5 and up.
        return ((kotlin.math.log10(light) - 1.0) / 4.5).toFloat().coerceIn(0f, 1f)
    }

    fun word(level: Float): String = when {
        level < 0.2f -> com.netgician.rplauncher.i18n.tr("Dark")
        level < 0.4f -> com.netgician.rplauncher.i18n.tr("Dim")
        level < 0.6f -> com.netgician.rplauncher.i18n.tr("Medium")
        level < 0.8f -> com.netgician.rplauncher.i18n.tr("Bright")
        else -> com.netgician.rplauncher.i18n.tr("Very bright")
    }

    /** 220 Hz in the dark up to 1760 Hz (three octaves) in bright light. */
    fun pitch(level: Float): Double = 220.0 * Math.pow(2.0, 3.0 * level.coerceIn(0f, 1f))
}
