package com.netgician.rplauncher.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import com.netgician.rplauncher.model.TileSpec

/**
 * Apps' own shortcuts ("Navigate home" in Maps, a web page added from Chrome) as home tiles.
 * Android lets only the home screen app see and open them, so these work while RP Launcher
 * is the home screen.
 */
object Shortcuts {
    data class Entry(val packageName: String, val id: String, val label: String, val longLabel: String)

    private fun apps(context: Context): LauncherApps? = context.getSystemService(LauncherApps::class.java)

    fun allowed(context: Context): Boolean = runCatching { apps(context)?.hasShortcutHostPermission() == true }.getOrDefault(false)

    /** The shortcuts an app offers in its long-press menu, plus any already on this home screen. */
    fun of(context: Context, packageName: String): List<Entry> {
        val la = apps(context) ?: return emptyList()
        if (!allowed(context)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        val found: List<ShortcutInfo> = runCatching { la.getShortcuts(query, Process.myUserHandle()).orEmpty() }.getOrDefault(emptyList())
        return found
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest && !it.isDynamic }, { it.rank }))
            .distinctBy { it.id }
            .map { entry(it) }
    }

    fun entry(s: ShortcutInfo): Entry {
        val short = (s.shortLabel ?: s.longLabel ?: s.id).toString()
        val long = (s.longLabel ?: s.shortLabel ?: s.id).toString()
        return Entry(s.`package`, s.id, short, long)
    }

    /** Opens a shortcut tile. Returns what to say when it can't. */
    fun start(context: Context, tile: TileSpec.Shortcut): String? {
        val la = apps(context) ?: return "Shortcuts don't work on this phone."
        if (!allowed(context)) return "Shortcuts only work while RP Launcher is the home screen."
        return try {
            la.startShortcut(tile.packageName, tile.id, null, null, Process.myUserHandle())
            null
        } catch (e: ActivityNotFoundException) {
            "${tile.shortcutLabel} isn't available any more. A helper can remove the tile in Setup."
        } catch (e: IllegalStateException) {
            "Unlock the phone first."
        } catch (e: Exception) {
            "${tile.shortcutLabel} isn't available any more. A helper can remove the tile in Setup."
        }
    }

    /**
     * Keeps exactly the shortcuts on the home screen pinned, so an app can't quietly take one
     * away, and releases the ones whose tiles were removed.
     */
    fun keepPinned(context: Context, tiles: List<TileSpec>) {
        val la = apps(context) ?: return
        if (!allowed(context)) return
        runCatching {
            val wanted = tiles.filterIsInstance<TileSpec.Shortcut>().groupBy({ it.packageName }, { it.id })
            val pinnedNow = la.getShortcuts(
                LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                Process.myUserHandle(),
            ).orEmpty().map { it.`package` }.toSet()
            for (pkg in pinnedNow + wanted.keys) {
                runCatching { la.pinShortcuts(pkg, wanted[pkg].orEmpty().distinct(), Process.myUserHandle()) }
            }
        }
    }
}
