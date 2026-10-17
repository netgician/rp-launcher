package com.netgician.rplauncher.model

/** Decides when night colours are on. Pure functions, so the rules can be tested. */
object NightMode {
    /** Below this many lux the room counts as dark. */
    const val DARK_LUX = 5f

    /** Above this many lux the room counts as lit. Between the two, nothing changes. */
    const val BRIGHT_LUX = 20f

    /** True between [fromHour] and [untilHour], wrapping past midnight. Equal hours mean never. */
    fun byClock(hour: Int, minute: Int, fromHour: Int, untilHour: Int): Boolean {
        val now = hour * 60 + minute
        val from = fromHour * 60
        val until = untilHour * 60
        return when {
            from == until -> false
            from < until -> now in from until until
            else -> now >= from || now < until
        }
    }

    /** Switches on in a dark room and off in a lit one, with a gap so it never flickers. */
    fun byLight(lux: Float, wasNight: Boolean): Boolean = when {
        lux < DARK_LUX -> true
        lux > BRIGHT_LUX -> false
        else -> wasNight
    }
}

/** Vibration patterns, as Android waveform timings: off, on, off, on… in milliseconds. */
object Landmarks {
    const val PULSE_MS = 70L
    const val GAP_MS = 140L

    /** One buzz per row, so the person can feel which row their finger is on. */
    fun rowPattern(row: Int): LongArray {
        val n = row.coerceIn(1, 9)
        return buildList {
            add(0L)
            repeat(n) { i ->
                if (i > 0) add(GAP_MS)
                add(PULSE_MS)
            }
        }.toLongArray()
    }

    /** Two quick taps: the page changed. */
    val PAGE_TURN: LongArray = longArrayOf(0L, 40L, 80L, 40L)

    /** Number of buzzes in a pattern. */
    fun pulses(pattern: LongArray): Int = pattern.size / 2
}

/** The camera's zoom steps and colour filters for the See tile. */
object CameraMath {
    const val ZOOM_STEP = 1.5f
    const val FROZEN_MAX_ZOOM = 8f

    fun closer(current: Float, max: Float): Float = minOf(max, current * ZOOM_STEP)
    fun wider(current: Float, min: Float): Float = maxOf(min, current / ZOOM_STEP)

    /** "Wide view", "Normal size" or "2.3 times closer". */
    fun describeZoom(z: Float): String = when {
        z < 0.95f -> "Wide view"
        z < 1.05f -> "Normal size"
        else -> "${String.format(java.util.Locale.US, "%.1f", z).removeSuffix(".0")} times closer"
    }

    /** "2.3×" for the status line. */
    fun shortZoom(z: Float): String = String.format(java.util.Locale.US, "%.1f×", z).replace(".0×", "×")

    /** How much the filters stretch contrast. */
    const val CONTRAST = 1.6f

    enum class Filter(val label: String) {
        NORMAL("Normal colours"),
        HIGH_CONTRAST("High contrast"),
        WHITE_ON_BLACK("White on black"),
        YELLOW_ON_BLACK("Yellow on black"),
    }

    enum class Light(val label: String) {
        OFF("Light off"),
        BRIGHTER("Brighter picture"),
        TORCH("Torch on"),
    }

    /**
     * A 4x5 colour matrix (Android and Compose layout, offsets in 0..255), or null for none.
     * Black text on white paper becomes white or yellow text on black, so pages don't glare.
     */
    fun matrix(f: Filter): FloatArray? {
        val c = CONTRAST
        val gray = floatArrayOf(0.299f * c, 0.587f * c, 0.114f * c, 0f, 128f * (1f - c))
        val inverted = floatArrayOf(-0.299f * c, -0.587f * c, -0.114f * c, 0f, 127f + 128f * c)
        val zero = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        val alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f)
        return when (f) {
            Filter.NORMAL -> null
            Filter.HIGH_CONTRAST -> gray + gray + gray + alpha
            Filter.WHITE_ON_BLACK -> inverted + inverted + inverted + alpha
            Filter.YELLOW_ON_BLACK -> inverted + inverted + zero + alpha
        }
    }

    /** Applies a matrix to one colour, clamped like the screen does. For tests. */
    fun apply(m: FloatArray, r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        fun row(i: Int): Int {
            val o = i * 5
            val v = m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 3] * 255 + m[o + 4]
            return v.toInt().coerceIn(0, 255)
        }
        return Triple(row(0), row(1), row(2))
    }
}
