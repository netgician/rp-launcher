package com.netgician.rplauncher.model

/**
 * A colour theme. Every text colour must reach 7:1 against every surface it sits on,
 * and no surface may be pure white (see ThemeRulesTest).
 *
 * [windowBrightness] sets the launcher window's own brightness (0..1); null leaves
 * the phone's brightness alone.
 */
data class LvTheme(
    val id: String,
    val name: String,
    val background: Long,
    val tileFill: Long,
    val foreground: Long,
    val accent: Long,
    val windowBrightness: Float? = null,
)

object Themes {
    const val PURE_WHITE = 0xFFFFFFFFL

    /** Light text on black. Off-white, never pure white. */
    val Dark = LvTheme(
        id = "dark",
        name = "White on black",
        background = 0xFF000000,
        tileFill = 0xFF141414,
        foreground = 0xFFF2F2F2,
        accent = 0xFFFFD60A,
    )

    val Yellow = LvTheme(
        id = "yellow",
        name = "Yellow on black",
        background = 0xFF000000,
        tileFill = 0xFF141414,
        foreground = 0xFFFFE14D,
        accent = 0xFFFFE14D,
    )

    /** Night themes: long-wavelength light at low brightness, to spare dark adaptation. */
    val NightAmber = LvTheme(
        id = "night_amber",
        name = "Night amber",
        background = 0xFF000000,
        tileFill = 0xFF000000,
        foreground = 0xFFFFB000,
        accent = 0xFFFFB000,
        windowBrightness = 0.12f,
    )

    /**
     * A pure red cannot reach 7:1 on black (the best is about 5.3:1), so this is a deep
     * red-orange: the reddest colour that still passes the contrast rule.
     */
    val NightRed = LvTheme(
        id = "night_red",
        name = "Night red",
        background = 0xFF000000,
        tileFill = 0xFF000000,
        foreground = 0xFFFF6E40,
        accent = 0xFFFF6E40,
        windowBrightness = 0.12f,
    )

    val all: List<LvTheme> = listOf(Dark, Yellow, NightAmber, NightRed)

    fun byId(id: String?): LvTheme = all.firstOrNull { it.id == id } ?: Dark

    fun next(current: LvTheme): LvTheme = all[(all.indexOf(current) + 1) % all.size]
}
