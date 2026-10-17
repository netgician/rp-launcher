package com.netgician.rplauncher.system

import android.content.Context
import com.netgician.rplauncher.model.ConfigJson
import com.netgician.rplauncher.model.LauncherConfig

/** Saves the configuration on the phone. A damaged file falls back to the defaults. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(): LauncherConfig {
        val text = prefs.getString(KEY, null) ?: return LauncherConfig()
        return runCatching { ConfigJson.decode(text) }.getOrElse { LauncherConfig() }
    }

    fun save(config: LauncherConfig) {
        prefs.edit().putString(KEY, ConfigJson.encode(config)).apply()
    }

    private companion object {
        const val KEY = "launcher_config"
    }
}

/**
 * If the launcher fails to start twice in a row, show the plain safe screen instead of
 * a black phone. A start counts as successful once the home screen has been up a few seconds.
 */
class CrashGuard(context: Context) {
    private val prefs = context.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)

    fun shouldShowSafeScreen(): Boolean = prefs.getInt(KEY, 0) >= 2

    fun markStarting() {
        prefs.edit().putInt(KEY, prefs.getInt(KEY, 0) + 1).commit()
    }

    fun markStarted() {
        prefs.edit().putInt(KEY, 0).apply()
    }

    fun reset() {
        prefs.edit().putInt(KEY, 0).commit()
    }

    private companion object {
        const val KEY = "unfinished_starts"
    }
}
