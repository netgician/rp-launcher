package com.netgician.rplauncher.model

import com.netgician.rplauncher.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** What a home-screen tile does. */
sealed class TileSpec {
    data object Phone : TileSpec()
    data object Messages : TileSpec()
    data object Contacts : TileSpec()
    data object Torch : TileSpec()
    data object See : TileSpec()
    data object Alerts : TileSpec()
    data object Sos : TileSpec()
    data object WhereAmI : TileSpec()
    data object Wifi : TileSpec()
    data object Bluetooth : TileSpec()
    data object Volume : TileSpec()
    /** Pulls down the phone's notification drop-down. */
    data object Notifications : TileSpec()
    /** Pulls down the quick settings (Wi-Fi, Bluetooth, torch, brightness...). */
    data object QuickSettings : TileSpec()
    data object AllApps : TileSpec()
    /** A list of the home screen's pages, to jump straight to one instead of paging through. */
    data object Pages : TileSpec()
    /** A kitchen timer: big choices, a big countdown, and a loud alarm with a big Stop. */
    data object Timer : TileSpec()
    data class App(val packageName: String, val className: String, val appLabel: String) : TileSpec()
    data class Contact(val name: String, val number: String) : TileSpec()
    /** One of an app's own shortcuts ("Navigate home", "New chat", a web page added from Chrome). */
    data class Shortcut(val packageName: String, val id: String, val shortcutLabel: String) : TileSpec()

    val label: String
        get() = when (this) {
            Phone -> tr("Phone")
            Messages -> tr("Texts")
            Contacts -> tr("Contacts")
            Torch -> tr("Torch")
            See -> tr("See")
            Alerts -> tr("Alerts")
            Sos -> tr("SOS")
            WhereAmI -> tr("Where am I")
            Wifi -> tr("Wi-Fi")
            Bluetooth -> tr("Bluetooth")
            Volume -> tr("Volume")
            Notifications -> tr("Notifications")
            QuickSettings -> tr("Quick settings")
            AllApps -> tr("All apps")
            Pages -> tr("Pages")
            Timer -> tr("Timer")
            is App -> appLabel
            is Contact -> name
            is Shortcut -> shortcutLabel
        }
}

/** What touch to hear says, so nobody has to guess that a double tap opens a tile. */
object Hints {
    val TOUCH: String get() = tr("Single tap to read aloud. Double tap to select.")

    /** Said on start-up and when leaving Setup, in a speech mode. */
    fun modeAnnouncement(mode: VisionMode): String = tr("%s mode. %s", mode.label, TOUCH)

    /** Said on a single tap: the tile, then (with hints on) how to select it. */
    fun heard(label: String, hintsOn: Boolean): String =
        if (hintsOn) tr("%s. Double tap to select.", label) else label
}

data class TextSize(private val en: String, val scale: Float) { val name: String get() = tr(en) }
data class VoicePitch(private val en: String, val pitch: Float) { val name: String get() = tr(en) }
data class TorchLevel(private val en: String, val strength: Float) { val name: String get() = tr(en) }
data class SpeechSpeed(private val en: String, val rate: Float) { val name: String get() = tr(en) }

/** When night colours switch on by themselves. */
enum class AutoNight(val id: String, private val en: String) {
    OFF("off", "Off"),
    CLOCK("clock", "By clock"),
    LIGHT("light", "By room light");

    val label: String get() = tr(en)

    companion object {
        fun byId(id: String?): AutoNight = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * How the person uses the phone, from sight-first to speech-first. Switching modes keeps
 * tiles and settings; it only changes how tiles respond and how they're laid out.
 */
enum class VisionMode(val id: String, private val en: String) {
    SIGHT("sight", "Sight first"),
    SIGHT_SPEECH("sight_speech", "Sight and speech"),
    SPEECH("speech", "Speech first");

    val label: String get() = tr(en)

    val explain: String
        get() = when (this) {
            SIGHT -> tr("Tap a tile to open it. Long press to hear it.")
            SIGHT_SPEECH -> Hints.TOUCH
            SPEECH -> tr("Tiles form one list. %s", Hints.TOUCH)
        }

    companion object {
        fun byId(id: String?): VisionMode = entries.firstOrNull { it.id == id } ?: SIGHT
    }
}

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
    val autoNight: AutoNight = AutoNight.OFF,
    val nightThemeId: String = Themes.NightAmber.id,
    val nightFromHour: Int = 20,
    val nightUntilHour: Int = 7,
    /** The launcher window's own brightness, 0..1, or null for the phone's setting. */
    val brightness: Float? = null,
    /** Vibration patterns that say where a tile is: buzz count = row number. */
    val touchLandmarks: Boolean = false,
    val visionMode: VisionMode = VisionMode.SIGHT,
    /** Speech first only: a black screen that still works by touch and speech. Setup is never darkened. */
    val darkScreen: Boolean = false,
    /** In speech modes: say how the gestures work, on start-up and after each single tap. */
    val spokenHints: Boolean = true,
    /** Who the SOS tile texts, in order. The first one gets the big Call button. */
    val sosContacts: List<TileSpec.Contact> = emptyList(),
    /** Seconds to cancel an SOS before it sends. */
    val sosSeconds: Int = 5,
    /** The person chose to keep texting in another app: the Texts tile opens it straight away. */
    val textsElsewhere: Boolean = false,
    /** Voice pitch. Lower voices are easier to hear with high-frequency hearing loss (common with Usher syndrome). */
    val speechPitch: Float = 1f,
    /** The phone voice to use, by name, or null for the phone's default. */
    val voiceName: String? = null,
    /** Torch brightness, 0..1, on phones that can dim it (Android 13 and later). */
    val torchStrength: Float = 1f,
    /** When the viewing window was last checked, in ms; 0 if never recorded. */
    val windowCheckedAt: Long = 0L,
    /** Remind a helper to re-check the viewing window after this many months; 0 for never. RP changes over time. */
    val windowReminderMonths: Int = 0,
    /** A bright frame around the viewing window, like the window check's, so its edges are easy to find. */
    val windowFrame: Boolean = true,
    /** The number Voicemail calls, set by a helper; null to use the one the phone has. */
    val voicemailNumber: String? = null,
    /** RP Launcher's own language: "en", "es", or null to follow the phone. */
    val language: String? = null,
) {
    /** True when the viewing window check is due again. It never changes the layout by itself. */
    fun windowCheckDue(now: Long): Boolean =
        windowReminderMonths > 0 && windowCheckedAt > 0 && now - windowCheckedAt > windowReminderMonths * 30L * 86_400_000L

    /** Tap to hear, double tap to open. */
    val touchToHear: Boolean get() = visionMode != VisionMode.SIGHT

    /** Speech first lays tiles out as one list, in a fixed order. */
    val homeCols: Int get() = if (visionMode == VisionMode.SPEECH) 1 else cols

    val theme: LvTheme get() = Themes.byId(themeId)
    val nightTheme: LvTheme get() = Themes.byId(nightThemeId)

    companion object {
        val DEFAULT_TILES: List<TileSpec> =
            listOf(TileSpec.Phone, TileSpec.Messages, TileSpec.Torch, TileSpec.See, TileSpec.AllApps)

        val COLUMN_CHOICES = listOf(1, 2, 3)
        val ROW_CHOICES = listOf(2, 3, 4, 5, 6)
        val TEXT_SIZES = listOf(
            TextSize("Small", 0.85f),
            TextSize("Medium", 1f),
            TextSize("Large", 1.25f),
            TextSize("Extra large", 1.5f),
        )
        val VOICE_PITCHES = listOf(
            VoicePitch("Normal", 1f),
            VoicePitch("Lower", 0.8f),
            VoicePitch("Lowest", 0.65f),
        )
        val TORCH_LEVELS = listOf(
            TorchLevel("Full", 1f),
            TorchLevel("Medium", 0.5f),
            TorchLevel("Low", 0.2f),
        )
        val WINDOW_REMINDERS = listOf(0, 3, 6)
        val SPEECH_SPEEDS = listOf(
            SpeechSpeed("Slow", 0.8f),
            SpeechSpeed("Normal", 1f),
            SpeechSpeed("Fast", 1.25f),
        )
        val NIGHT_FROM_HOURS = listOf(18, 19, 20, 21, 22, 23)
        val NIGHT_UNTIL_HOURS = listOf(5, 6, 7, 8, 9)
        val BRIGHTNESS_CHOICES: List<Float?> = listOf(null, 0.75f, 0.5f, 0.25f)
        val NIGHT_THEMES: List<LvTheme> = listOf(Themes.NightAmber, Themes.NightRed)
    }
}

/** Cycles to the next choice in a list, wrapping round. Unknown values start at the first. */
fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1) % size]

/** The setup file: the whole configuration as JSON, so it can move to the next phone. */
object ConfigJson {
    private const val VERSION = 5

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
        put("autoNight", c.autoNight.id)
        put("nightTheme", c.nightThemeId)
        put("nightFrom", c.nightFromHour)
        put("nightUntil", c.nightUntilHour)
        if (c.brightness != null) put("brightness", c.brightness.toDouble())
        put("touchLandmarks", c.touchLandmarks)
        put("visionMode", c.visionMode.id)
        put("darkScreen", c.darkScreen)
        put("spokenHints", c.spokenHints)
        put("sos", JSONArray().apply {
            c.sosContacts.forEach { put(JSONObject().apply { put("name", it.name); put("number", it.number) }) }
        })
        put("sosSeconds", c.sosSeconds)
        put("textsElsewhere", c.textsElsewhere)
        put("pitch", c.speechPitch.toDouble())
        if (c.voiceName != null) put("voice", c.voiceName)
        put("torchStrength", c.torchStrength.toDouble())
        put("windowCheckedAt", c.windowCheckedAt)
        put("windowReminderMonths", c.windowReminderMonths)
        put("windowFrame", c.windowFrame)
        c.voicemailNumber?.let { put("voicemail", it) }
        c.language?.let { put("language", it) }
    }.toString()

    private fun encodeTile(t: TileSpec): JSONObject = JSONObject().apply {
        when (t) {
            TileSpec.Phone -> put("type", "phone")
            TileSpec.Messages -> put("type", "messages")
            TileSpec.Contacts -> put("type", "contacts")
            TileSpec.Torch -> put("type", "torch")
            TileSpec.See -> put("type", "see")
            TileSpec.Alerts -> put("type", "alerts")
            TileSpec.Sos -> put("type", "sos")
            TileSpec.WhereAmI -> put("type", "where_am_i")
            TileSpec.Wifi -> put("type", "wifi")
            TileSpec.Bluetooth -> put("type", "bluetooth")
            TileSpec.Volume -> put("type", "volume")
            TileSpec.Notifications -> put("type", "notifications")
            TileSpec.QuickSettings -> put("type", "quick_settings")
            TileSpec.AllApps -> put("type", "all_apps")
            TileSpec.Pages -> put("type", "pages")
            TileSpec.Timer -> put("type", "timer")
            is TileSpec.App -> {
                put("type", "app"); put("pkg", t.packageName); put("cls", t.className); put("label", t.appLabel)
            }
            is TileSpec.Contact -> {
                put("type", "contact"); put("name", t.name); put("number", t.number)
            }
            is TileSpec.Shortcut -> {
                put("type", "shortcut"); put("pkg", t.packageName); put("id", t.id); put("label", t.shortcutLabel)
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
        val nightTheme = Themes.byId(o.optString("nightTheme", d.nightThemeId))
        val brightness = if (o.has("brightness")) {
            o.optDouble("brightness", Double.NaN).toFloat().takeIf { !it.isNaN() }?.coerceIn(0.05f, 1f)
        } else null
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
            autoNight = AutoNight.byId(o.optString("autoNight", d.autoNight.id)),
            nightThemeId = if (nightTheme in LauncherConfig.NIGHT_THEMES) nightTheme.id else d.nightThemeId,
            nightFromHour = o.optInt("nightFrom", d.nightFromHour).coerceIn(0, 23),
            nightUntilHour = o.optInt("nightUntil", d.nightUntilHour).coerceIn(0, 23),
            brightness = brightness,
            touchLandmarks = o.optBoolean("touchLandmarks", d.touchLandmarks),
            visionMode = VisionMode.byId(o.optString("visionMode", d.visionMode.id)),
            darkScreen = o.optBoolean("darkScreen", d.darkScreen),
            spokenHints = o.optBoolean("spokenHints", d.spokenHints),
            sosContacts = o.optJSONArray("sos")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { decodeTile(it.put("type", "contact")) as? TileSpec.Contact } }
            } ?: d.sosContacts,
            sosSeconds = o.optInt("sosSeconds", d.sosSeconds).let { s -> if (s in Sos.COUNTDOWN_CHOICES) s else d.sosSeconds },
            textsElsewhere = o.optBoolean("textsElsewhere", d.textsElsewhere),
            speechPitch = o.optDouble("pitch", d.speechPitch.toDouble()).toFloat().coerceIn(0.5f, 1.5f),
            voiceName = o.optString("voice", "").ifBlank { null },
            torchStrength = o.optDouble("torchStrength", d.torchStrength.toDouble()).toFloat().coerceIn(0.05f, 1f),
            windowCheckedAt = o.optLong("windowCheckedAt", d.windowCheckedAt).coerceAtLeast(0L),
            windowReminderMonths = o.optInt("windowReminderMonths", d.windowReminderMonths)
                .let { m -> if (m in LauncherConfig.WINDOW_REMINDERS) m else d.windowReminderMonths },
            windowFrame = o.optBoolean("windowFrame", d.windowFrame),
            voicemailNumber = o.optString("voicemail", "").filter { it.isDigit() || it in "+*#" }.ifBlank { null },
            language = o.optString("language", "").takeIf { it == "en" || it == "es" },
        )
    }

    private fun decodeTile(o: JSONObject): TileSpec? = when (o.optString("type")) {
        "phone" -> TileSpec.Phone
        "messages" -> TileSpec.Messages
        "contacts" -> TileSpec.Contacts
        "torch" -> TileSpec.Torch
        "see" -> TileSpec.See
        "alerts" -> TileSpec.Alerts
        "sos" -> TileSpec.Sos
        "where_am_i" -> TileSpec.WhereAmI
        "wifi" -> TileSpec.Wifi
        "bluetooth" -> TileSpec.Bluetooth
        "volume" -> TileSpec.Volume
        "notifications" -> TileSpec.Notifications
        "quick_settings" -> TileSpec.QuickSettings
        "all_apps" -> TileSpec.AllApps
        "pages" -> TileSpec.Pages
        "timer" -> TileSpec.Timer
        "app" -> {
            val pkg = o.optString("pkg"); val cls = o.optString("cls")
            if (pkg.isBlank() || cls.isBlank()) null else TileSpec.App(pkg, cls, o.optString("label", pkg))
        }
        "contact" -> {
            val number = o.optString("number")
            if (number.isBlank()) null else TileSpec.Contact(o.optString("name", number), number)
        }
        "shortcut" -> {
            val pkg = o.optString("pkg"); val id = o.optString("id")
            if (pkg.isBlank() || id.isBlank()) null else TileSpec.Shortcut(pkg, id, o.optString("label", id))
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
