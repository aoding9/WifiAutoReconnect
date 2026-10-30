package com.tw.wifireconnect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 定时巡检：配置仍为启用但服务被系统杀掉时，自动重新拉起前台服务（恢复通知栏）。
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            ACTION_TICK -> Unit
            else -> return
        }

        val prefs = AppPrefs(context)
        if (!prefs.enabled) {
            cancel(context)
            return
        }

        if (!WifiReconnectService.isAlive) {
            Log.i(TAG, "watchdog: service dead, restarting (${intent?.action})")
            WifiReconnectService.start(context)
        }
        schedule(context)
    }

    companion object {
        private const val TAG = "WifiWatchdog"
        const val ACTION_TICK = "com.tw.wifireconnect.WATCHDOG_TICK"
        private const val REQ = 2002
        /** 约 5 分钟巡检一次 */
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(context)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: Exception) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
        }

        private fun pending(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION_TICK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(context, REQ, intent, flags)
        }
    }
}
