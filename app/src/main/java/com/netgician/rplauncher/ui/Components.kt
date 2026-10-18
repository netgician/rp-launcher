package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netgician.rplauncher.R
import com.netgician.rplauncher.model.Layout
import com.netgician.rplauncher.model.LvTheme
import com.netgician.rplauncher.model.Paging
import com.netgician.rplauncher.model.Slot
import com.netgician.rplauncher.model.ViewingWindow
import com.netgician.rplauncher.model.Box as Area

/** Atkinson Hyperlegible pulls apart letters that otherwise look alike. */
val AtkinsonFamily = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

/** The current theme, typeface and text size, available to every screen. */
data class LvStyle(val theme: LvTheme, val font: FontFamily, val textScale: Float) {
    val bg = Color(theme.background)
    val tile = Color(theme.tileFill)
    val fg = Color(theme.foreground)
    val accent = Color(theme.accent)
}

val LocalLv = staticCompositionLocalOf<LvStyle> { error("LvStyle not provided") }

/**
 * How tiles respond to a tap. With touch to hear on, a tap speaks the tile and a double tap
 * opens it; the tile last heard gets a thicker outline. Off whenever TalkBack is running.
 */
data class TapMode(
    val touchToHear: Boolean = false,
    val heard: String? = null,
    val onHear: (String) -> Unit = {},
    /** Plain speech, for a long press on a Setup row. */
    val speak: (String) -> Unit = {},
)

val LocalTapMode = compositionLocalOf { TapMode() }

/** Places a composable at a box computed by the layout rules, in dp. */
fun Modifier.place(a: Area): Modifier = this.offset(a.left.dp, a.top.dp).size(a.width.dp, a.height.dp)

/**
 * Everything on screen lives inside the person's viewing window. Outside it is plain black
 * and holds nothing, because they can't see it.
 */
@Composable
fun InWindow(window: ViewingWindow, content: @Composable BoxScope.() -> Unit) {
    val lv = LocalLv.current
    val frame = LocalWindowFrame.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val r = window.rectIn(maxWidth.value, maxHeight.value)
        // With the frame on, everything sits just inside it, so no tile is ever under the line.
        val framed = if (frame) Modifier.border(BorderStroke(FRAME_DP.dp, lv.accent)).padding((FRAME_DP + 4).dp) else Modifier
        Box(modifier = Modifier.place(r).background(lv.bg).then(framed), content = content)
    }
}

/** The frame around the viewing window (Setup, Window frame): as thick as the window check's. */
private const val FRAME_DP = 6

/** True when the frame around the viewing window is on. */
val LocalWindowFrame = staticCompositionLocalOf { false }

/**
 * Bold text that shrinks until it fits, instead of being cut off or breaking a word
 * across lines. Its own semantics are cleared: the parent supplies what TalkBack reads.
 *
 * The size is worked out by measuring before anything is drawn, every time the space or the
 * text changes, so a label can never be left blank or half-sized after a layout change.
 */
@Composable
fun FitText(
    text: String,
    color: Color,
    maxSp: Float,
    modifier: Modifier = Modifier,
    minSp: Float = 12f,
    maxLines: Int = 2,
    align: TextAlign = TextAlign.Center,
) {
    val lv = LocalLv.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val style = { sp: Float ->
            TextStyle(
                color = color,
                fontSize = sp.sp,
                lineHeight = (sp * 1.15f).sp,
                fontFamily = lv.font,
                fontWeight = FontWeight.Bold,
                textAlign = align,
            )
        }
        val size = remember(text, maxSp, minSp, maxLines, w, h, lv.font, align) {
            val limit = if (h == Constraints.Infinity) Constraints(maxWidth = w) else Constraints(maxWidth = w, maxHeight = h)
            var sp = maxSp
            while (sp > minSp) {
                val r = measurer.measure(
                    text, style(sp), overflow = TextOverflow.Clip, softWrap = true, maxLines = maxLines, constraints = limit,
                )
                if (!r.hasVisualOverflow && !breaksAWord(text, r)) break
                sp = (sp * 0.9f).coerceAtLeast(minSp)
            }
            sp
        }
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            style = style(size),
            maxLines = maxLines,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun breaksAWord(text: String, r: TextLayoutResult): Boolean {
    for (line in 0 until r.lineCount - 1) {
        val end = r.getLineEnd(line)
        if (end in 1 until text.length) {
            val before = text[end - 1]
            val after = text[end]
            if (!before.isWhitespace() && !after.isWhitespace() && before != '-') return true
        }
    }
    return false
}

sealed interface TileIcon {
    data class Vector(val image: ImageVector) : TileIcon
    data class Drawable(@DrawableRes val id: Int) : TileIcon
}

/**
 * A big tile: a flat dark surface, a thick bright outline, and icon and label centred
 * together as one block. A long press can speak it. Emphasis is a thicker outline,
 * never a bright fill, so nothing glares.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    label: String,
    modifier: Modifier = Modifier,
    spoken: String = label,
    icon: TileIcon? = null,
    emphasized: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val lv = LocalLv.current
    val tap = LocalTapMode.current
    val view = LocalView.current
    val shape = RoundedCornerShape(12.dp)
    val heardNow = tap.touchToHear && tap.heard == spoken
    val longClick = onLongPress?.let { speak ->
        {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speak()
        }
    }
    val press = if (tap.touchToHear) {
        Modifier.combinedClickable(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tap.onHear(spoken)
            },
            onDoubleClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
            onLongClick = longClick,
        )
    } else {
        Modifier.combinedClickable(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
            onLongClick = longClick,
        )
    }
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(lv.tile)
            .border(BorderStroke(if (emphasized || heardNow) 8.dp else 4.dp, lv.accent), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                role = Role.Button
            }
            .then(press)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val showIcon = icon != null && maxHeight >= 96.dp
        val iconSize: Dp = minOf(maxHeight * 0.38f, maxWidth * 0.5f, 56.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showIcon && icon != null) {
                TileIconImage(icon, lv.fg, iconSize)
                Spacer(Modifier.height(6.dp))
            }
            FitText(label, lv.fg, maxSp = 26f * lv.textScale, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TileIconImage(icon: TileIcon, tint: Color, size: Dp) {
    val painter = when (icon) {
        is TileIcon.Vector -> rememberVectorPainter(icon.image)
        is TileIcon.Drawable -> painterResource(icon.id)
    }
    Image(
        painter = painter,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

/** A full-width row for helper screens: a tile with "Title: value" text. A long press speaks it. */
@Composable
fun SettingRow(title: String, value: String? = null, onClick: () -> Unit) {
    val lv = LocalLv.current
    val tap = LocalTapMode.current
    val text = if (value == null) title else "$title: $value"
    Tile(
        label = text,
        modifier = Modifier.fillMaxWidth().heightIn(min = (64f * lv.textScale).dp),
        onLongPress = { tap.speak(text) },
        onClick = onClick,
    )
}

/** A line of text that speaks itself when tapped. */
@Composable
fun SpokenText(
    text: String,
    modifier: Modifier,
    onSpeak: (String) -> Unit,
    spoken: String = text,
    maxSp: Float = 28f,
    maxLines: Int = 1,
) {
    val lv = LocalLv.current
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .clickable { onSpeak(spoken) },
        contentAlignment = Alignment.Center,
    ) {
        FitText(text, lv.fg, maxSp = maxSp * lv.textScale, maxLines = maxLines, modifier = Modifier.fillMaxWidth())
    }
}

/** Where a tile sits on its page, counting from 1. Used for touch landmarks. */
data class TilePos(val row: Int, val col: Int)

/**
 * A header, a grid of tiles and, when there are more items than fit, big Next and Back
 * tiles plus a "Page 2 of 3" line. The grid comes from [Layout.grid], so it obeys the
 * same rules the tests check.
 */
@Composable
fun <T> PagedTiles(
    items: List<T>,
    page: Int,
    onPage: (newPage: Int, pageCount: Int) -> Unit,
    cols: Int,
    rows: Int,
    onSpeak: (String) -> Unit,
    header: @Composable (Modifier) -> Unit,
    minTarget: Float = Layout.MIN_TARGET_DP,
    /** Null for the usual header row; 0 for none, when the screen shows its title elsewhere. */
    headerHeight: Float? = null,
    /** Told how the items fell into pages, for the Pages tile. */
    onPaged: ((List<List<T>>) -> Unit)? = null,
    tile: @Composable (item: T, modifier: Modifier, pos: TilePos) -> Unit,
) {
    val lv = LocalLv.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerH = headerHeight ?: (52f * lv.textScale)
        val grid = Layout.grid(
            windowWidth = maxWidth.value,
            windowHeight = maxHeight.value,
            headerHeight = headerH,
            footerHeight = 44f * lv.textScale,
            wantCols = cols,
            wantRows = rows,
            itemCount = items.size,
            minTarget = minTarget,
        )
        val pages = Paging.paginate(items, grid.tiles.size)
        val current = page.coerceIn(0, pages.lastIndex)
        if (onPaged != null) {
            val byPage = pages.map { p -> p.mapNotNull { (it as? Slot.Item<T>)?.value } }
            SideEffect { onPaged(byPage) }
        }

        if (headerH > 0f) header(Modifier.place(grid.header))

        pages[current].forEachIndexed { i, slot ->
            val area = grid.tiles.getOrNull(i) ?: return@forEachIndexed
            val m = Modifier.place(area)
            val pos = TilePos(row = i / grid.cols + 1, col = i % grid.cols + 1)
            when (slot) {
                is Slot.Item -> tile(slot.value, m, pos)
                Slot.Next -> Tile(
                    label = tr("Next"),
                    modifier = m,
                    spoken = tr("Next page"),
                    icon = TileIcon.Vector(Icons.AutoMirrored.Filled.ArrowForward),
                ) { onPage(current + 1, pages.size) }
                Slot.Back -> Tile(
                    label = tr("Back"),
                    modifier = m,
                    spoken = tr("Previous page"),
                    icon = TileIcon.Vector(Icons.AutoMirrored.Filled.ArrowBack),
                ) { onPage(current - 1, pages.size) }
            }
        }

        grid.footer?.let { f ->
            SpokenText(
                text = tr("Page %s of %s", current + 1, pages.size),
                modifier = Modifier.place(f),
                onSpeak = onSpeak,
                maxSp = 22f,
            )
        }
    }
}
