package com.netgician.rplauncher.model

import kotlin.math.max

/** Where the header, tiles and page footer sit inside the viewing window, in dp. */
data class GridResult(
    val cols: Int,
    val rows: Int,
    val header: Box,
    val tiles: List<Box>,
    val footer: Box?,
)

object Layout {
    /** Android's minimum touch target. */
    const val MIN_TARGET_DP = 48f
    const val GAP_DP = 8f
    const val PAD_DP = 8f

    /**
     * Lays out a header, a grid of tiles and (only when paging is needed) a footer.
     * Columns and rows shrink until every tile is at least [minTarget] dp on each side.
     */
    fun grid(
        windowWidth: Float,
        windowHeight: Float,
        headerHeight: Float,
        footerHeight: Float,
        wantCols: Int,
        wantRows: Int,
        itemCount: Int,
        minTarget: Float = MIN_TARGET_DP,
    ): GridResult {
        val single = build(windowWidth, windowHeight, headerHeight, null, wantCols, wantRows, minTarget)
        return if (itemCount <= single.tiles.size) single
        else build(windowWidth, windowHeight, headerHeight, footerHeight, wantCols, wantRows, minTarget)
    }

    private fun build(
        windowWidth: Float,
        windowHeight: Float,
        headerHeight: Float,
        footerHeight: Float?,
        wantCols: Int,
        wantRows: Int,
        minTarget: Float,
    ): GridResult {
        val innerWidth = max(0f, windowWidth - 2 * PAD_DP)
        val header = Box(PAD_DP, PAD_DP, innerWidth, headerHeight)
        val gridTop = header.bottom + GAP_DP
        val footerSpace = if (footerHeight != null) footerHeight + GAP_DP else 0f
        val gridHeight = max(0f, windowHeight - PAD_DP - gridTop - footerSpace)

        fun tileWidth(cols: Int) = (innerWidth - (cols - 1) * GAP_DP) / cols
        fun tileHeight(rows: Int) = (gridHeight - (rows - 1) * GAP_DP) / rows

        var cols = wantCols.coerceAtLeast(1)
        var rows = wantRows.coerceAtLeast(1)
        while (cols > 1 && tileWidth(cols) < minTarget) cols--
        while (rows > 1 && tileHeight(rows) < minTarget) rows--
        // A paged screen needs room for Back, one item and Next, even in a tiny window.
        // Widen first (keeps tiles big enough), and only add rows as a last resort.
        if (footerHeight != null) {
            while (cols * rows < 3 && tileWidth(cols + 1) >= minTarget) cols++
            while (cols * rows < 3) rows++
        }

        val w = max(0f, tileWidth(cols))
        val h = max(0f, tileHeight(rows))
        val tiles = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                add(Box(PAD_DP + c * (w + GAP_DP), gridTop + r * (h + GAP_DP), w, h))
            }
        }
        val footer = footerHeight?.let {
            Box(PAD_DP, gridTop + gridHeight + GAP_DP, innerWidth, it)
        }
        return GridResult(cols, rows, header, tiles, footer)
    }
}
