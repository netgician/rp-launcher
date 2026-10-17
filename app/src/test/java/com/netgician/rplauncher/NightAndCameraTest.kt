package com.netgician.rplauncher

import com.netgician.rplauncher.model.CameraMath
import com.netgician.rplauncher.model.Contrast
import com.netgician.rplauncher.model.Landmarks
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.NightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightAndCameraTest {

    @Test
    fun nightByClockWrapsPastMidnight() {
        // 8 PM to 7 AM
        assertFalse(NightMode.byClock(19, 59, 20, 7))
        assertTrue(NightMode.byClock(20, 0, 20, 7))
        assertTrue(NightMode.byClock(23, 30, 20, 7))
        assertTrue(NightMode.byClock(0, 0, 20, 7))
        assertTrue(NightMode.byClock(6, 59, 20, 7))
        assertFalse(NightMode.byClock(7, 0, 20, 7))
        assertFalse(NightMode.byClock(12, 0, 20, 7))
    }

    @Test
    fun nightByClockWithinOneDayAndNever() {
        assertTrue(NightMode.byClock(2, 0, 1, 5))
        assertFalse(NightMode.byClock(6, 0, 1, 5))
        for (h in 0..23) assertFalse("equal hours mean never", NightMode.byClock(h, 0, 8, 8))
    }

    @Test
    fun everyClockChoiceGivesSomeNightAndSomeDay() {
        for (from in LauncherConfig.NIGHT_FROM_HOURS) for (until in LauncherConfig.NIGHT_UNTIL_HOURS) {
            val night = (0..23).count { NightMode.byClock(it, 0, from, until) }
            assertTrue("from $from until $until", night in 1..23)
        }
    }

    @Test
    fun roomLightHasAGapSoItNeverFlickers() {
        assertTrue(NightMode.byLight(1f, wasNight = false))
        assertFalse(NightMode.byLight(100f, wasNight = true))
        // In the gap, it keeps whatever it was.
        val middle = (NightMode.DARK_LUX + NightMode.BRIGHT_LUX) / 2
        assertTrue(NightMode.byLight(middle, wasNight = true))
        assertFalse(NightMode.byLight(middle, wasNight = false))
        assertTrue(NightMode.DARK_LUX < NightMode.BRIGHT_LUX)
    }

    @Test
    fun nightThemesStayDimAndPassContrast() {
        for (t in LauncherConfig.NIGHT_THEMES) {
            assertTrue(t.name, Contrast.ratio(t.foreground, t.background) >= 7.0)
            assertTrue(t.name, (t.windowBrightness ?: 1f) <= 0.2f)
        }
    }

    @Test
    fun brightnessChoicesAreSensible() {
        assertNull("first choice is the phone's own setting", LauncherConfig.BRIGHTNESS_CHOICES.first())
        for (b in LauncherConfig.BRIGHTNESS_CHOICES.filterNotNull()) assertTrue(b in 0.05f..1f)
    }

    @Test
    fun rowPatternBuzzesOncePerRow() {
        for (row in 1..6) {
            val p = Landmarks.rowPattern(row)
            assertEquals("row $row", row, Landmarks.pulses(p))
            assertEquals("starts at once", 0L, p.first())
            assertTrue("every timing is non-negative", p.all { it >= 0 })
        }
        assertEquals(2, Landmarks.pulses(Landmarks.PAGE_TURN))
    }

    @Test
    fun zoomStopsAtTheLimits() {
        assertEquals(1.5f, CameraMath.closer(1f, 10f), 0.001f)
        assertEquals(10f, CameraMath.closer(9f, 10f), 0.001f)
        assertEquals(0.6f, CameraMath.wider(0.9f, 0.6f), 0.001f)
        assertEquals(1f, CameraMath.wider(1f, 1f), 0.001f)
        // Repeated taps always reach both ends.
        var z = 1f
        repeat(30) { z = CameraMath.closer(z, 8f) }
        assertEquals(8f, z, 0.001f)
        repeat(30) { z = CameraMath.wider(z, 0.5f) }
        assertEquals(0.5f, z, 0.001f)
    }

    @Test
    fun zoomIsDescribedInWords() {
        assertEquals("Wide view", CameraMath.describeZoom(0.6f))
        assertEquals("Normal size", CameraMath.describeZoom(1f))
        assertEquals("2 times closer", CameraMath.describeZoom(2f))
        assertEquals("2.3 times closer", CameraMath.describeZoom(2.25f))
        assertEquals("1.5×", CameraMath.shortZoom(1.5f))
        assertEquals("2×", CameraMath.shortZoom(2f))
    }

    @Test
    fun filtersTurnLightPaperDark() {
        val white = Triple(255, 255, 255)
        val black = Triple(0, 0, 0)
        assertNull(CameraMath.matrix(CameraMath.Filter.NORMAL))

        val w = CameraMath.matrix(CameraMath.Filter.WHITE_ON_BLACK)!!
        assertEquals("white paper turns black", black, CameraMath.apply(w, 255, 255, 255))
        assertEquals("black print turns white", white, CameraMath.apply(w, 0, 0, 0))

        val y = CameraMath.matrix(CameraMath.Filter.YELLOW_ON_BLACK)!!
        assertEquals("white paper turns black", black, CameraMath.apply(y, 255, 255, 255))
        assertEquals("black print turns yellow", Triple(255, 255, 0), CameraMath.apply(y, 0, 0, 0))

        val g = CameraMath.matrix(CameraMath.Filter.HIGH_CONTRAST)!!
        val (r, gg, b) = CameraMath.apply(g, 128, 128, 128)
        assertTrue("mid grey stays mid grey", r in 120..135 && r == gg && gg == b)
        assertEquals(white, CameraMath.apply(g, 230, 230, 230))
        assertEquals(black, CameraMath.apply(g, 25, 25, 25))
    }

    @Test
    fun everyMatrixIsFourByFive() {
        for (f in CameraMath.Filter.entries) {
            CameraMath.matrix(f)?.let { assertEquals(f.name, 20, it.size) }
        }
    }
}
