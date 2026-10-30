package com.tw.wifireconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppPrefs(context)
        if (prefs.enabled && prefs.bootStart) {
            WifiReconnectService.start(context)
        }
    }
}
