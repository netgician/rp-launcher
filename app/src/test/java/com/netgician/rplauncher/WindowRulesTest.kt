package com.netgician.rplauncher

import com.netgician.rplauncher.model.Box
import com.netgician.rplauncher.model.Layout
import com.netgician.rplauncher.model.ViewingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec rules: every tile, button and alert lies fully inside the viewing window, across a
 * range of window and screen sizes; tap targets are at least 48 dp.
 */
class WindowRulesTest {

    // Usable screen areas in dp: small, typical and large phones, portrait and landscape.
    private val screens = listOf(320f to 560f, 360f to 640f, 393f to 852f, 412f to 915f, 480f to 1000f, 852f to 393f)

    private fun windows(): List<ViewingWindow> {
        val all = mutableListOf<ViewingWindow>()
        var w = ViewingWindow(0f, 0f, 0f).clamped()
        // Every size the Bigger/Smaller buttons can reach, at several heights.
        for (step in 0..20) {
            for (cy in listOf(0f, 0.3f, 0.5f, 0.7f, 1f)) all += w.copy(centerYFraction = cy)
            w = w.bigger()
        }
        return all
    }

    @Test
    fun windowAlwaysFitsTheScreen() {
        for ((sw, sh) in screens) for (win in windows()) {
            val r = win.rectIn(sw, sh)
            assertTrue("window $win escapes a ${sw}x$sh screen: $r", Box(0f, 0f, sw, sh).contains(r))
        }
    }

    @Test
    fun windowNeverSmallerThanTheControlsNeed() {
        for ((sw, sh) in screens) for (win in windows()) {
            val r = win.rectIn(sw, sh)
            assertTrue(r.width >= minOf(ViewingWindow.MIN_WIDTH_DP, sw) - 0.01f)
            assertTrue(r.height >= minOf(ViewingWindow.MIN_HEIGHT_DP, sh) - 0.01f)
        }
    }

    @Test
    fun everyTileSitsInsideTheWindowAndIsBigEnough() {
        val textScales = listOf(0.85f, 1f, 1.25f, 1.5f)
        for ((sw, sh) in screens) for (win in windows()) for (scale in textScales) {
            val r = win.rectIn(sw, sh)
            val inside = Box(0f, 0f, r.width, r.height)
            for (cols in 1..3) for (rows in 2..6) for (items in listOf(1, 6, 40)) {
                val g = Layout.grid(r.width, r.height, 52f * scale, 44f * scale, cols, rows, items)
                val what = "screen ${sw}x$sh window ${r.width}x${r.height} scale $scale grid ${cols}x$rows items $items"
                assertTrue("header escapes: $what", inside.contains(g.header))
                g.footer?.let { assertTrue("footer escapes: $what", inside.contains(it)) }
                assertTrue("no tiles: $what", g.tiles.isNotEmpty())
                for (t in g.tiles) {
                    assertTrue("tile escapes: $what", inside.contains(t))
                    assertTrue("tile under 48 dp: $what -> $t", t.width >= Layout.MIN_TARGET_DP && t.height >= Layout.MIN_TARGET_DP)
                }
                if (items > g.tiles.size) assertTrue("paging needs 3 slots: $what", g.tiles.size >= 3)
            }
        }
    }

    @Test
    fun tilesNeverOverlap() {
        val g = Layout.grid(300f, 500f, 52f, 44f, 3, 4, 12)
        for (i in g.tiles.indices) for (j in i + 1 until g.tiles.size) {
            val a = g.tiles[i]
            val b = g.tiles[j]
            val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
            assertTrue("tiles $i and $j overlap", !overlap)
        }
    }

    @Test
    fun footerOnlyWhenPaging() {
        val fits = Layout.grid(360f, 600f, 52f, 44f, 2, 3, 6)
        assertEquals(null, fits.footer)
        val pages = Layout.grid(360f, 600f, 52f, 44f, 2, 3, 7)
        assertTrue(pages.footer != null)
    }

    @Test
    fun biggerAndSmallerStopAtTheLimits() {
        var w = ViewingWindow()
        repeat(40) { w = w.bigger() }
        assertTrue(w.isLargest)
        assertEquals(w, w.bigger())
        repeat(40) { w = w.smaller() }
        assertTrue(w.isSmallest)
        assertEquals(w, w.smaller())
    }
}
