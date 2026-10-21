package com.netgician.rplauncher

import android.app.Application
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.system.ConfigStore

/** Picks RP Launcher's language before anything else starts: screens, calls, texts arriving. */
class RpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.choose(runCatching { ConfigStore(this).load().language }.getOrNull())
        // A timer survives RP Launcher closing and the phone restarting: set its alarm again.
        runCatching { com.netgician.rplauncher.system.Timer.restore(this) }
        runCatching { com.netgician.rplauncher.system.Alarms.restore(this) }
        com.netgician.rplauncher.system.BatteryWatch.start(this)
    }
}
