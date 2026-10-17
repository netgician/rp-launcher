package com.netgician.rplauncher

import com.netgician.rplauncher.model.Contrast
import com.netgician.rplauncher.model.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec rule: every text and background pair in every theme is at least 7:1. No pure white. */
class ThemeRulesTest {

    @Test
    fun contrastFormulaMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFF, 0xFF000000), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777, 0xFF777777), 0.001)
    }

    @Test
    fun everyThemeReachesSevenToOne() {
        for (t in Themes.all) {
            val pairs = mapOf(
                "text on background" to Contrast.ratio(t.foreground, t.background),
                "text on tile" to Contrast.ratio(t.foreground, t.tileFill),
                "outline on background" to Contrast.ratio(t.accent, t.background),
                "outline on tile" to Contrast.ratio(t.accent, t.tileFill),
            )
            for ((what, ratio) in pairs) {
                assertTrue("${t.name}: $what is ${"%.2f".format(ratio)}:1, below 7:1", ratio >= 7.0)
            }
        }
    }

    @Test
    fun noPureWhiteAnywhere() {
        for (t in Themes.all) {
            for (c in listOf(t.background, t.tileFill, t.foreground, t.accent)) {
                assertNotEquals("${t.name} uses pure white", Themes.PURE_WHITE, c)
            }
        }
    }

    @Test
    fun surfacesStayDark() {
        // Backgrounds and tile fills must stay near black, so nothing glares.
        for (t in Themes.all) {
            assertTrue("${t.name} background too bright", Contrast.luminance(t.background) <= 0.01)
            assertTrue("${t.name} tile fill too bright", Contrast.luminance(t.tileFill) <= 0.01)
        }
    }

    @Test
    fun nightThemesDimTheWindow() {
        for (t in listOf(Themes.NightAmber, Themes.NightRed)) {
            val level = t.windowBrightness
            assertTrue("${t.name} should dim the screen", level != null && level <= 0.2f)
        }
    }

    @Test
    fun themeIdsAreUniqueAndCycle() {
        assertEquals(Themes.all.size, Themes.all.map { it.id }.toSet().size)
        var t = Themes.Dark
        repeat(Themes.all.size) { t = Themes.next(t) }
        assertEquals(Themes.Dark, t)
        assertEquals(Themes.Dark, Themes.byId("no-such-theme"))
    }
}
