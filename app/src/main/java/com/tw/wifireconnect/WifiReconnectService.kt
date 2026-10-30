package com.tw.wifireconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class WifiReconnectService : Service() {

    private lateinit var prefs: AppPrefs
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectScheduled = false
    private var lastAttemptAt = 0L
    private var lastLog = "服务已启动"

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
            @Suppress("DEPRECATION")
            val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            if (info == null) return

            when (info.detailedState) {
                NetworkInfo.DetailedState.DISCONNECTED,
                NetworkInfo.DetailedState.FAILED,
                NetworkInfo.DetailedState.IDLE -> {
                    lastLog = "检测到断开: ${info.detailedState}"
                    scheduleReconnect("state=${info.detailedState}")
                }
                NetworkInfo.DetailedState.CONNECTED -> {
                    val ssid = WifiHelper.currentSsid(this@WifiReconnectService)
                    lastLog = "已连接: $ssid"
                    updateNotification()
                    reconnectScheduled = false
                    handler.removeCallbacks(reconnectRunnable)
                }
                else -> Unit
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!prefs.enabled) {
                stopSelfSafe()
                return
            }
            val target = prefs.targetSsid
            if (target.isNotEmpty() && !WifiHelper.isConnectedTo(this@WifiReconnectService, target)) {
                scheduleReconnect("poll_miss")
            }
            val intervalMs = prefs.intervalSec.coerceIn(AppPrefs.MIN_INTERVAL, AppPrefs.MAX_INTERVAL) * 1000L
            handler.postDelayed(this, intervalMs)
            updateNotification()
        }
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        attemptReconnect("scheduled")
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        createChannel()
        startForeground(NOTIFY_ID, buildNotification())
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
        handler.post(pollRunnable)
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT_NOW -> {
                attemptReconnect("manual")
            }
            ACTION_REFRESH -> {
                updateNotification()
            }
        }
        if (!prefs.enabled) {
            stopSelfSafe()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
        Log.i(TAG, "service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleReconnect(reason: String) {
        if (!prefs.enabled || prefs.targetSsid.isEmpty()) return
        if (WifiHelper.isConnectedTo(this, prefs.targetSsid)) return

        val now = System.currentTimeMillis()
        val minGap = prefs.intervalSec.coerceIn(AppPrefs.MIN_INTERVAL, AppPrefs.MAX_INTERVAL) * 1000L
        val wait = (lastAttemptAt + minGap - now).coerceAtLeast(800L)

        if (reconnectScheduled) {
            lastLog = "已排队重连($reason)"
            return
        }
        reconnectScheduled = true
        lastLog = "将在 ${wait / 1000}s 后重连($reason)"
        updateNotification()
        handler.postDelayed(reconnectRunnable, wait)
    }

    private fun attemptReconnect(reason: String) {
        if (!prefs.enabled) return
        val target = prefs.targetSsid
        if (target.isEmpty()) {
            lastLog = "未选择目标 WiFi"
            updateNotification()
            return
        }
        if (WifiHelper.isConnectedTo(this, target)) {
            lastLog = "已在目标网络，跳过"
            updateNotification()
            return
        }

        lastAttemptAt = System.currentTimeMillis()
        val result = WifiHelper.connectSaved(this, target)
        lastLog = "[$reason] ${if (result.ok) "成功" else "失败"}: ${result.detail}"
        Log.i(TAG, lastLog)
        updateNotification()
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    private fun stopSelfSafe() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiFi 自动重连",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "保持后台监听 WiFi 断线"
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val ssid = prefs.targetSsid.ifEmpty { "未选择" }
        val content = if (prefs.targetSsid.isEmpty()) {
            getString(R.string.notification_idle)
        } else {
            getString(R.string.notification_watching, ssid) + "\n" + lastLog
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification())
    }

    private fun pendingImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    fun currentStatusText(): String = lastLog

    companion object {
        private const val TAG = "WifiReconnectSvc"
        private const val CHANNEL_ID = "wifi_reconnect"
        private const val NOTIFY_ID = 1001

        const val ACTION_STOP = "com.tw.wifireconnect.STOP"
        const val ACTION_RECONNECT_NOW = "com.tw.wifireconnect.RECONNECT_NOW"
        const val ACTION_REFRESH = "com.tw.wifireconnect.REFRESH"
        const val ACTION_STATUS_CHANGED = "com.tw.wifireconnect.STATUS"

        fun start(context: Context) {
            val intent = Intent(context, WifiReconnectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WifiReconnectService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun reconnectNow(context: Context) {
            val intent = Intent(context, WifiReconnectService::class.java)
                .setAction(ACTION_RECONNECT_NOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
