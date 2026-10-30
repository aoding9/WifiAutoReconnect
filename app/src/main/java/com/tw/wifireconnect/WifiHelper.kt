package com.tw.wifireconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

object WifiHelper {

    fun wifiManager(context: Context): WifiManager {
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    fun normalizeSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("\"").removeSuffix("\"")
    }

    fun quotedSsid(ssid: String): String {
        val clean = normalizeSsid(ssid)
        return if (clean.startsWith("\"")) clean else "\"$clean\""
    }

    /** 已保存网络 SSID 列表（去重） */
    @Suppress("DEPRECATION")
    fun savedSsids(context: Context): List<String> {
        val wm = wifiManager(context)
        val configs: List<WifiConfiguration> = try {
            wm.configuredNetworks ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        val fromConfig = configs.mapNotNull { normalizeSsid(it.SSID) }.filter { it.isNotEmpty() }

        val current = normalizeSsid(wm.connectionInfo?.ssid)
        val merged = LinkedHashSet<String>()
        if (current.isNotEmpty() && !current.equals("<unknown ssid>", true)) {
            merged.add(current)
        }
        merged.addAll(fromConfig)
        return merged.toList().sorted()
    }

    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String {
        val ssid = normalizeSsid(wifiManager(context).connectionInfo?.ssid)
        return if (ssid.equals("<unknown ssid>", true)) "" else ssid
    }

    @Suppress("DEPRECATION")
    fun isWifiEnabled(context: Context): Boolean = wifiManager(context).isWifiEnabled

    @Suppress("DEPRECATION")
    fun isConnectedTo(context: Context, targetSsid: String): Boolean {
        val target = normalizeSsid(targetSsid)
        if (target.isEmpty()) return false
        if (!isWifiEnabled(context)) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ni: NetworkInfo? = cm.activeNetworkInfo
        if (ni == null || !ni.isConnected || ni.type != ConnectivityManager.TYPE_WIFI) {
            return false
        }
        return normalizeSsid(currentSsid(context)).equals(target, ignoreCase = true)
    }

    /**
     * 强制连接已保存的目标 WiFi。
     * 1) enableNetwork + reconnect
     * 2) 失败则开关 WiFi 触发系统重连
     */
    @Suppress("DEPRECATION")
    fun connectSaved(context: Context, targetSsid: String): ConnectResult {
        val target = normalizeSsid(targetSsid)
        if (target.isEmpty()) return ConnectResult(false, "未设置目标 WiFi")

        val wm = wifiManager(context)
        if (!wm.isWifiEnabled) {
            wm.isWifiEnabled = true
            SystemClock.sleep(1200)
        }

        val configs = try {
            wm.configuredNetworks ?: emptyList()
        } catch (e: SecurityException) {
            return ConnectResult(false, "无权限读取已保存网络")
        }

        val match = configs.firstOrNull {
            normalizeSsid(it.SSID).equals(target, ignoreCase = true)
        }

        if (match != null) {
            try {
                wm.disconnect()
                val enabled = wm.enableNetwork(match.networkId, true)
                val reconnected = wm.reconnect()
                return ConnectResult(
                    enabled || reconnected,
                    "enableNetwork=$enabled reconnect=$reconnected id=${match.networkId}"
                )
            } catch (e: Exception) {
                return ConnectResult(false, "连接异常: ${e.message}")
            }
        }

        // 列表里找不到时：开关 WiFi，依赖系统自动加入
        return toggleWifi(wm, "未在已保存列表找到「$target」，已尝试开关 WiFi")
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(wm: WifiManager, reason: String): ConnectResult {
        return try {
            wm.isWifiEnabled = false
            SystemClock.sleep(1500)
            wm.isWifiEnabled = true
            ConnectResult(true, reason)
        } catch (e: Exception) {
            ConnectResult(false, "开关 WiFi 失败: ${e.message}")
        }
    }

    data class ConnectResult(val ok: Boolean, val detail: String)
}
