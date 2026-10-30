package com.tw.wifireconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED
            && action != Intent.ACTION_MY_PACKAGE_REPLACED
            && action != Intent.ACTION_USER_PRESENT
        ) {
            return
        }
        val prefs = AppPrefs(context)
        if (!prefs.enabled) return
        if (action == Intent.ACTION_BOOT_COMPLETED && !prefs.bootStart) return

        if (!WifiReconnectService.isAlive) {
            WifiReconnectService.start(context)
        }
        WatchdogReceiver.schedule(context)
    }
}
