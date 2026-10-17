package com.netgician.rplauncher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import java.text.Collator

data class AppEntry(val label: String, val packageName: String, val className: String)

object Apps {
    /** Every app with a launcher icon, except this one, sorted by name. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    className = it.activityInfo.name,
                )
            }
            .distinctBy { it.packageName to it.className }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun launchIntent(packageName: String, className: String): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

data class BatteryState(val percent: Int, val charging: Boolean)

object Battery {
    fun read(context: Context): BatteryState {
        val bm = context.getSystemService(BatteryManager::class.java)
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return BatteryState(percent = percent, charging = bm?.isCharging == true)
    }
}
