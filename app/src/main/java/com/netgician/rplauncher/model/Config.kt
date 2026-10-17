package com.netgician.rplauncher.model

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** What a home-screen tile does. */
sealed class TileSpec {
    data object Phone : TileSpec()
    data object Messages : TileSpec()
    data object Contacts : TileSpec()
    data object Torch : TileSpec()
    data object AllApps : TileSpec()
    data class App(val packageName: String, val className: String, val appLabel: String) : TileSpec()
    data class Contact(val name: String, val number: String) : TileSpec()

    val label: String
        get() = when (this) {
            Phone -> "Phone"
            Messages -> "Messages"
            Contacts -> "Contacts"
            Torch -> "Torch"
            AllApps -> "All apps"
            is App -> appLabel
            is Contact -> name
        }
}

data class TextSize(val name: String, val scale: Float)
data class SpeechSpeed(val name: String, val rate: Float)

data class LauncherConfig(
    val themeId: String = Themes.Dark.id,
    val cols: Int = 2,
    val rows: Int = 3,
    val textScale: Float = 1f,
    val speechRate: Float = 1f,
    val speakOnLongPress: Boolean = true,
    val windowChecked: Boolean = false,
    val window: ViewingWindow = ViewingWindow(),
    val pinHash: String? = null,
    val tiles: List<TileSpec> = DEFAULT_TILES,
) {
    val theme: LvTheme get() = Themes.byId(themeId)

    companion object {
        val DEFAULT_TILES: List<TileSpec> =
            listOf(TileSpec.Phone, TileSpec.Messages, TileSpec.Torch, TileSpec.Contacts, TileSpec.AllApps)

        val COLUMN_CHOICES = listOf(1, 2, 3)
        val ROW_CHOICES = listOf(2, 3, 4, 5, 6)
        val TEXT_SIZES = listOf(
            TextSize("Small", 0.85f),
            TextSize("Medium", 1f),
            TextSize("Large", 1.25f),
            TextSize("Extra large", 1.5f),
        )
        val SPEECH_SPEEDS = listOf(
            SpeechSpeed("Slow", 0.8f),
            SpeechSpeed("Normal", 1f),
            SpeechSpeed("Fast", 1.25f),
        )
    }
}

/** Cycles to the next choice in a list, wrapping round. Unknown values start at the first. */
fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1) % size]

/** The setup file: the whole configuration as JSON, so it can move to the next phone. */
object ConfigJson {
    private const val VERSION = 1

    fun encode(c: LauncherConfig): String = JSONObject().apply {
        put("version", VERSION)
        put("theme", c.themeId)
        put("cols", c.cols)
        put("rows", c.rows)
        put("textScale", c.textScale.toDouble())
        put("speechRate", c.speechRate.toDouble())
        put("speakOnLongPress", c.speakOnLongPress)
        put("windowChecked", c.windowChecked)
        put("window", JSONObject().apply {
            put("w", c.window.widthFraction.toDouble())
            put("h", c.window.heightFraction.toDouble())
            put("cy", c.window.centerYFraction.toDouble())
        })
        if (c.pinHash != null) put("pinHash", c.pinHash)
        put("tiles", JSONArray().apply { c.tiles.forEach { put(encodeTile(it)) } })
    }.toString()

    private fun encodeTile(t: TileSpec): JSONObject = JSONObject().apply {
        when (t) {
            TileSpec.Phone -> put("type", "phone")
            TileSpec.Messages -> put("type", "messages")
            TileSpec.Contacts -> put("type", "contacts")
            TileSpec.Torch -> put("type", "torch")
            TileSpec.AllApps -> put("type", "all_apps")
            is TileSpec.App -> {
                put("type", "app"); put("pkg", t.packageName); put("cls", t.className); put("label", t.appLabel)
            }
            is TileSpec.Contact -> {
                put("type", "contact"); put("name", t.name); put("number", t.number)
            }
        }
    }

    /** Reads a file the person picked. Refuses JSON that isn't an RP Launcher setup. */
    fun decodeSetupFile(text: String): LauncherConfig {
        val o = JSONObject(text)
        require(o.has("version") && o.has("tiles")) { "Not an RP Launcher setup file" }
        return decode(text)
    }

    /** Reads saved settings. Anything missing or broken falls back to the default, never a crash. */
    fun decode(text: String): LauncherConfig {
        val d = LauncherConfig()
        val o = JSONObject(text)
        val w = o.optJSONObject("window")
        val window = if (w == null) d.window else ViewingWindow(
            widthFraction = w.optDouble("w", d.window.widthFraction.toDouble()).toFloat(),
            heightFraction = w.optDouble("h", d.window.heightFraction.toDouble()).toFloat(),
            centerYFraction = w.optDouble("cy", d.window.centerYFraction.toDouble()).toFloat(),
        ).clamped()
        val tilesJson = o.optJSONArray("tiles")
        val tiles = if (tilesJson == null) d.tiles else buildList {
            for (i in 0 until tilesJson.length()) {
                tilesJson.optJSONObject(i)?.let { decodeTile(it) }?.let { add(it) }
            }
        }
        return LauncherConfig(
            themeId = Themes.byId(o.optString("theme", d.themeId)).id,
            cols = o.optInt("cols", d.cols).coerceIn(1, 3),
            rows = o.optInt("rows", d.rows).coerceIn(2, 6),
            textScale = o.optDouble("textScale", d.textScale.toDouble()).toFloat().coerceIn(0.85f, 1.5f),
            speechRate = o.optDouble("speechRate", d.speechRate.toDouble()).toFloat().coerceIn(0.5f, 2f),
            speakOnLongPress = o.optBoolean("speakOnLongPress", d.speakOnLongPress),
            windowChecked = o.optBoolean("windowChecked", d.windowChecked),
            window = window,
            pinHash = o.optString("pinHash", "").ifBlank { null },
            tiles = tiles,
        )
    }

    private fun decodeTile(o: JSONObject): TileSpec? = when (o.optString("type")) {
        "phone" -> TileSpec.Phone
        "messages" -> TileSpec.Messages
        "contacts" -> TileSpec.Contacts
        "torch" -> TileSpec.Torch
        "all_apps" -> TileSpec.AllApps
        "app" -> {
            val pkg = o.optString("pkg"); val cls = o.optString("cls")
            if (pkg.isBlank() || cls.isBlank()) null else TileSpec.App(pkg, cls, o.optString("label", pkg))
        }
        "contact" -> {
            val number = o.optString("number")
            if (number.isBlank()) null else TileSpec.Contact(o.optString("name", number), number)
        }
        else -> null
    }
}

/** The helper PIN is stored only as a hash. It keeps tiles from being changed by accident. */
object Pin {
    const val MIN_LENGTH = 4

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("rp-launcher:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun matches(pin: String, storedHash: String?): Boolean = storedHash != null && hash(pin) == storedHash
}
