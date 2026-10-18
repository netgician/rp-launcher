package com.netgician.rplauncher.ui

import android.content.Context
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.ViewingWindow

/**
 * Test builds only: the build machine opens each screen in Demo mode and photographs it for the
 * app stores (scripts/store_screenshots.sh). Release builds ignore this.
 */
object StoreShots {
    const val ACTION = "com.netgician.rplauncher.STORE_SHOT"
    const val EXTRA_SCREEN = "screen"

    private val TILES = listOf(
        TileSpec.Phone, TileSpec.Messages, TileSpec.See, TileSpec.Torch,
        TileSpec.Sos, TileSpec.Contact(Demo.CALLER_NAME, Demo.CALLER_NUMBER),
    )

    fun show(state: AppState, context: Context, screen: String) {
        if (!Demo.on) {
            Demo.set(true)
            state.texts.demoChanged()
        }
        val theme = when (screen) {
            "home-night" -> "night_red"
            "picture", "recents", "checklist" -> "dark"
            else -> "yellow"
        }
        state.update {
            it.copy(
                windowChecked = true,
                // A roomy window, so the store pictures are easy to read at thumbnail size.
                window = ViewingWindow(0.94f, 0.8f, 0.5f).clamped(),
                windowCheckedAt = System.currentTimeMillis(),
                tiles = TILES,
                themeId = theme,
                autoNight = AutoNight.OFF,
                sosContacts = listOf(TileSpec.Contact(Demo.CALLER_NAME, Demo.CALLER_NUMBER), TileSpec.Contact("Bob", "+19195550111")),
            )
        }
        when (screen) {
            "home", "home-night" -> {
                state.homePage = 0
                state.go(Screen.Home)
            }
            "texts" -> state.go(Screen.Texts)
            "conversation" -> state.go(Screen.Conversation(1))
            "group" -> state.go(Screen.Conversation(3))
            "picture" -> state.go(Screen.Picture(3, listOf(Demo.PICTURE_ID), 0))
            "phone" -> state.go(Screen.PhoneHome)
            "recents" -> state.go(Screen.Recents)
            "call" -> com.netgician.rplauncher.calls.CallActivity.openDemo(context, true, Demo.CALLER_NAME, Demo.CALLER_NUMBER)
            "sos" -> state.go(Screen.Sos(practice = true))
            "checklist" -> state.go(Screen.Checklist(firstRun = false))
            "pages" -> state.go(Screen.Pages)
        }
    }
}
