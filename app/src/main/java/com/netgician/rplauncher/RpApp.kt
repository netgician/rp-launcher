package com.netgician.rplauncher

import android.app.Application
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.system.ConfigStore

/** Picks RP Launcher's language before anything else starts: screens, calls, texts arriving. */
class RpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.choose(runCatching { ConfigStore(this).load().language }.getOrNull())
    }
}
