package com.tw.wifireconnect

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var bootStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    var targetSsid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SSID, value).apply()

    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    companion object {
        const val PREFS_NAME = "wifi_auto_reconnect"
        const val KEY_ENABLED = "enabled"
        const val KEY_BOOT = "boot_start"
        const val KEY_SSID = "target_ssid"
        const val KEY_INTERVAL = "interval_sec"
        const val DEFAULT_INTERVAL = 3
        const val MIN_INTERVAL = 3
        const val MAX_INTERVAL = 3600
    }
}
