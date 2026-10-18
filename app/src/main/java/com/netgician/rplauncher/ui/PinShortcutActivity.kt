package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.content.pm.LauncherApps
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.Shortcuts
import com.netgician.rplauncher.system.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Add to home screen" from another app (a web page in Chrome, a chat, a playlist). Android
 * asks the home screen app to confirm; this asks the person, with two big buttons, and adds a
 * tile at the end of the home screen.
 */
class PinShortcutActivity : ComponentActivity() {
    private var speaker: Speaker? = null
    private var done by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.BLACK), navigationBarStyle = SystemBarStyle.dark(Color.BLACK))
        super.onCreate(savedInstanceState)
        val request = runCatching { getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent) }.getOrNull()
        val info = request?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val store = ConfigStore(this)
        val config = store.load()
        val sp = Speaker(this).apply {
            rate = config.speechRate
            pitch = config.speechPitch
            voiceName = config.voiceName
        }
        speaker = sp
        val entry = Shortcuts.entry(info)
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, 0)).toString()
        }.getOrDefault(tr("An app"))

        fun close(words: String) {
            done = true
            sp.speak(words)
            lifecycleScope.launch {
                delay(1_800)
                finish()
            }
        }

        fun add() {
            if (done) return
            val ok = runCatching { request.accept() }.getOrDefault(false)
            if (!ok) {
                close(tr("It couldn't be added. Nothing changed."))
                return
            }
            val tile = TileSpec.Shortcut(info.`package`, info.id, entry.label)
            val now = store.load()
            if (now.tiles.none { it is TileSpec.Shortcut && it.packageName == tile.packageName && it.id == tile.id }) {
                store.save(now.copy(tiles = now.tiles + tile))
            }
            close(tr("%s added. It's the last tile on your home screen.", entry.label))
        }

        setContent {
            val theme = if (NightNow.active && config.autoNight != AutoNight.OFF) config.nightTheme else config.theme
            val style = remember(theme) { LvStyle(theme, AtkinsonFamily, config.textScale) }
            CompositionLocalProvider(LocalLv provides style, LocalWindowFrame provides config.windowFrame) {
                val question = tr("%s wants to add %s to your home screen.", appName, entry.label)
                LaunchedEffect(Unit) {
                    delay(400)
                    sp.speak(tr("%s Tap Add, or Don't add.", question))
                }
                InWindow(config.window) {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpokenText(
                            tr("Add %s?", entry.label), Modifier.fillMaxWidth().height((96f * style.textScale).dp), { sp.speak(it) },
                            spoken = question, maxSp = 30f, maxLines = 2,
                        )
                        Tile(
                            tr("Add"), Modifier.fillMaxWidth().weight(2f), spoken = tr("Add %s to the home screen", entry.label),
                            icon = TileIcon.Vector(Icons.Filled.Add), emphasized = true, onLongPress = { sp.speak(tr("Add")) },
                        ) { add() }
                        Tile(
                            tr("Don't add"), Modifier.fillMaxWidth().weight(1f), icon = TileIcon.Vector(Icons.Filled.Close),
                            onLongPress = { sp.speak(tr("Don't add")) },
                        ) { if (!done) close(tr("Not added")) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }
}
