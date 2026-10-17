package com.netgician.rplauncher.model

import kotlin.math.max
import kotlin.math.min

/** A rectangle in dp. */
data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** True if [other] lies fully inside this box, allowing for float rounding. */
    fun contains(other: Box, slack: Float = 0.01f): Boolean =
        other.left >= left - slack && other.top >= top - slack &&
            other.right <= right + slack && other.bottom <= bottom + slack
}

/**
 * The part of the screen the person can actually see, set by the first-run check.
 * Stored as fractions of the usable screen so it survives rotation and new phones.
 */
data class ViewingWindow(
    val widthFraction: Float = 0.8f,
    val heightFraction: Float = 0.6f,
    val centerYFraction: Float = 0.5f,
) {
    fun clamped(): ViewingWindow = ViewingWindow(
        widthFraction = widthFraction.coerceIn(MIN_WIDTH_FRACTION, 1f),
        heightFraction = heightFraction.coerceIn(MIN_HEIGHT_FRACTION, 1f),
        centerYFraction = centerYFraction.coerceIn(0f, 1f),
    )

    fun bigger(): ViewingWindow = copy(widthFraction = widthFraction + STEP, heightFraction = heightFraction + STEP).clamped()
    fun smaller(): ViewingWindow = copy(widthFraction = widthFraction - STEP, heightFraction = heightFraction - STEP).clamped()
    fun moveUp(): ViewingWindow = copy(centerYFraction = centerYFraction - STEP).clamped()
    fun moveDown(): ViewingWindow = copy(centerYFraction = centerYFraction + STEP).clamped()

    val isLargest: Boolean get() = widthFraction >= 1f && heightFraction >= 1f
    val isSmallest: Boolean get() = widthFraction <= MIN_WIDTH_FRACTION && heightFraction <= MIN_HEIGHT_FRACTION

    /**
     * The window's rectangle inside a usable area of [areaWidth] x [areaHeight] dp.
     * Never smaller than the minimum dp size (so the controls still fit), never larger
     * than the area, and always fully inside it.
     */
    fun rectIn(areaWidth: Float, areaHeight: Float): Box {
        val c = clamped()
        val w = min(areaWidth, max(areaWidth * c.widthFraction, MIN_WIDTH_DP))
        val h = min(areaHeight, max(areaHeight * c.heightFraction, MIN_HEIGHT_DP))
        val left = (areaWidth - w) / 2f
        val top = (areaHeight * c.centerYFraction - h / 2f).coerceIn(0f, max(0f, areaHeight - h))
        return Box(left, top, w, h)
    }

    companion object {
        const val STEP = 0.05f
        const val MIN_WIDTH_FRACTION = 0.4f
        const val MIN_HEIGHT_FRACTION = 0.3f
        const val MIN_WIDTH_DP = 220f
        const val MIN_HEIGHT_DP = 300f
    }
}
