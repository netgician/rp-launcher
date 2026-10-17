package com.netgician.rplauncher

import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.Paging
import com.netgician.rplauncher.model.Pin
import com.netgician.rplauncher.model.Slot
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.ViewingWindow
import com.netgician.rplauncher.model.after
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingAndConfigTest {

    @Test
    fun everyItemAppearsOnceInOrder() {
        for (count in 0..50) for (slots in 1..18) {
            val items = (1..count).toList()
            val pages = Paging.paginate(items, slots)
            val flat = pages.flatten().filterIsInstance<Slot.Item<*>>().map { it.value as Int }
            assertEquals("count $count slots $slots", items, flat)
        }
    }

    @Test
    fun nextAndBackOnlyWhereTheyBelong() {
        for (count in 1..50) for (slots in 3..18) {
            val pages = Paging.paginate((1..count).toList(), slots)
            pages.forEachIndexed { i, page ->
                assertTrue(page.size <= slots)
                assertEquals("Back on page $i", i > 0, page.first() == Slot.Back)
                assertEquals("Next on page $i", i < pages.lastIndex, page.last() == Slot.Next)
                assertTrue("page $i has no item", page.any { it is Slot.Item })
            }
        }
    }

    @Test
    fun singlePageHasNoPagingTiles() {
        val pages = Paging.paginate(listOf("a", "b", "c"), 6)
        assertEquals(1, pages.size)
        assertFalse(pages[0].any { it == Slot.Next || it == Slot.Back })
    }

    @Test
    fun setupFileRoundTrips() {
        val c = LauncherConfig(
            themeId = "night_amber",
            cols = 3,
            rows = 5,
            textScale = 1.25f,
            speechRate = 0.8f,
            speakOnLongPress = false,
            windowChecked = true,
            window = ViewingWindow(0.55f, 0.45f, 0.4f),
            pinHash = Pin.hash("2468"),
            tiles = listOf(
                TileSpec.Torch,
                TileSpec.Contact("Mary O'Neil", "+1 919 555 0100"),
                TileSpec.App("org.example.radio", "org.example.radio.Main", "Radio \"Live\""),
                TileSpec.Phone,
                TileSpec.See,
                TileSpec.AllApps,
            ),
            autoNight = AutoNight.LIGHT,
            nightThemeId = "night_red",
            nightFromHour = 21,
            nightUntilHour = 6,
            brightness = 0.5f,
            touchLandmarks = true,
        )
        assertEquals(c, ConfigJson.decode(ConfigJson.encode(c)))
        assertEquals(c, ConfigJson.decodeSetupFile(ConfigJson.encode(c)))
    }

    @Test
    fun version1SetupFilesStillLoad() {
        val v1 = """{"version":1,"theme":"yellow","cols":2,"rows":3,"tiles":[{"type":"phone"},{"type":"torch"}]}"""
        val c = ConfigJson.decodeSetupFile(v1)
        assertEquals("yellow", c.themeId)
        assertEquals(listOf(TileSpec.Phone, TileSpec.Torch), c.tiles)
        assertEquals(AutoNight.OFF, c.autoNight)
        assertEquals(null, c.brightness)
        assertFalse(c.touchLandmarks)
    }

    @Test
    fun aDayThemeCannotBeTheNightTheme() {
        val c = ConfigJson.decode("""{"nightTheme":"yellow"}""")
        assertEquals(LauncherConfig().nightThemeId, c.nightThemeId)
    }

    @Test
    fun defaultHomeFitsOnePage() {
        // Default tiles plus Setup fill the default 2x3 grid exactly, so nobody starts with paging.
        val d = LauncherConfig()
        assertEquals(d.cols * d.rows, d.tiles.size + 1)
    }

    @Test
    fun brokenSettingsFallBackToDefaults() {
        val d = LauncherConfig()
        val c = ConfigJson.decode("""{"theme":"nope","cols":99,"rows":-3,"tiles":[{"type":"alien"},{"type":"contact","number":""}]}""")
        assertEquals(d.themeId, c.themeId)
        assertEquals(3, c.cols)
        assertEquals(2, c.rows)
        assertTrue(c.tiles.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignFilesAreRefused() {
        ConfigJson.decodeSetupFile("""{"hello":"world"}""")
    }

    @Test
    fun pinIsHashedAndChecked() {
        val h = Pin.hash("1234")
        assertNotEquals("1234", h)
        assertTrue(Pin.matches("1234", h))
        assertFalse(Pin.matches("4321", h))
        assertFalse(Pin.matches("1234", null))
    }

    @Test
    fun choicesCycle() {
        assertEquals(2, LauncherConfig.COLUMN_CHOICES.after(1))
        assertEquals(1, LauncherConfig.COLUMN_CHOICES.after(3))
        assertEquals(LauncherConfig.ROW_CHOICES.first(), LauncherConfig.ROW_CHOICES.after(99))
    }
}
