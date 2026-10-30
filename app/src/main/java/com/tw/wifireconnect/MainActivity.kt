package com.tw.wifireconnect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tw.wifireconnect.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private var ssidList: List<String> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusPollCount = 0

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    /** 服务异步启动：短轮询直到 isAlive 或超时，避免界面卡在「正在拉起」 */
    private val statusPollRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            statusPollCount++
            if (WifiReconnectService.isAlive || statusPollCount >= 10) {
                statusPollCount = 0
                return
            }
            uiHandler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        binding.switchEnable.isChecked = prefs.enabled
        binding.switchBoot.isChecked = prefs.bootStart
        binding.editInterval.setText(prefs.intervalSec.toString())

        binding.btnRefresh.setOnClickListener { loadWifiList(true) }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnBattery.setOnClickListener { openBatteryWhitelist() }
        binding.btnTest.setOnClickListener {
            saveSettings(showToast = false)
            if (prefs.targetSsid.isEmpty()) {
                Toast.makeText(this, R.string.hint_ssid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = WifiHelper.connectSaved(this, prefs.targetSsid, enableRadioIfOff = true)
            Toast.makeText(
                this,
                if (result.ok) getString(R.string.reconnect_ok) else getString(R.string.reconnect_fail),
                Toast.LENGTH_SHORT
            ).show()
            refreshStatus(result.detail)
            if (prefs.enabled) {
                WifiReconnectService.reconnectNow(this)
            }
        }

        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // 打开即生效：一并写入当前 WiFi/间隔等设置并启动服务
                if (!saveSettings(showToast = false) && binding.switchEnable.isChecked) {
                    binding.switchEnable.isChecked = false
                } else if (prefs.enabled) {
                    refreshStatus("已启用，等待服务就绪")
                    pollServiceStatus()
                }
            } else {
                prefs.enabled = false
                stopStatusPoll()
                WifiReconnectService.stop(this)
                refreshStatus("已停止服务")
                // 停止也是异步的，补两次刷新
                uiHandler.postDelayed({ refreshStatus() }, 400L)
            }
        }

        ensurePermissions()
        loadWifiList(false)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(WifiReconnectService.ACTION_STATUS_CHANGED))
        // 配置启用但服务被杀（通知没了）：打开界面时自动拉起
        val revived = prefs.enabled && !WifiReconnectService.isAlive
        if (revived) {
            WifiReconnectService.ensureRunning(this)
            refreshStatus("检测到服务已停止，正在重新拉起")
            pollServiceStatus()
        } else {
            refreshStatus()
        }
    }

    override fun onPause() {
        stopStatusPoll()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun pollServiceStatus() {
        stopStatusPoll()
        statusPollCount = 0
        uiHandler.postDelayed(statusPollRunnable, 200L)
    }

    private fun stopStatusPoll() {
        uiHandler.removeCallbacks(statusPollRunnable)
        statusPollCount = 0
    }

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            loadWifiList(true)
        }
    }

    private fun loadWifiList(fromUser: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (fromUser) {
                Toast.makeText(this, R.string.need_location, Toast.LENGTH_SHORT).show()
                ensurePermissions()
            }
        }

        ssidList = WifiHelper.savedSsids(this)
        val display = if (ssidList.isEmpty()) {
            listOf(getString(R.string.hint_ssid))
        } else {
            ssidList
        }
        binding.spinnerWifi.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, display)

        val saved = prefs.targetSsid
        if (saved.isNotEmpty()) {
            val idx = ssidList.indexOfFirst { it.equals(saved, ignoreCase = true) }
            if (idx >= 0) {
                binding.spinnerWifi.setSelection(idx)
            }
        }
        refreshStatus()
    }

    /** @return 是否保存成功 */
    private fun saveSettings(showToast: Boolean = true): Boolean {
        val intervalText = binding.editInterval.text?.toString()?.trim().orEmpty()
        val interval = intervalText.toIntOrNull()
        if (interval == null || interval < AppPrefs.MIN_INTERVAL || interval > AppPrefs.MAX_INTERVAL) {
            Toast.makeText(this, R.string.interval_invalid, Toast.LENGTH_SHORT).show()
            return false
        }

        val selected = binding.spinnerWifi.selectedItem?.toString().orEmpty()
        val ssid = if (ssidList.isEmpty() || selected == getString(R.string.hint_ssid)) {
            prefs.targetSsid
        } else {
            selected
        }

        if (binding.switchEnable.isChecked && ssid.isEmpty()) {
            Toast.makeText(this, R.string.hint_ssid, Toast.LENGTH_SHORT).show()
            binding.switchEnable.isChecked = false
            return false
        }

        prefs.intervalSec = interval
        prefs.bootStart = binding.switchBoot.isChecked
        prefs.targetSsid = ssid
        prefs.enabled = binding.switchEnable.isChecked

        if (prefs.enabled) {
            WifiReconnectService.start(this)
            pollServiceStatus()
        } else {
            WifiReconnectService.stop(this)
        }

        if (showToast) {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        refreshStatus("设置已保存")
        return true
    }

    private fun refreshStatus(extra: String? = null) {
        refreshBatteryStatus()

        val wifiOn = WifiHelper.isWifiEnabled(this)
        val current = WifiHelper.currentSsid(this).ifEmpty { "未连接" }
        val target = prefs.targetSsid.ifEmpty { "未选择" }
        val matched = prefs.targetSsid.isNotEmpty() &&
            WifiHelper.isConnectedTo(this, prefs.targetSsid)
        val batteryOk = isIgnoringBatteryOptimizations()

        val serviceText = when {
            !prefs.enabled -> "未启用"
            WifiReconnectService.isAlive -> "运行中（通知栏应可见）"
            else -> "已启用但服务未在运行（正在尝试拉起）"
        }

        binding.textStatus.text = buildString {
            append("服务：").append(serviceText).append('\n')
            append("开机自启：").append(if (prefs.bootStart) "是" else "否").append('\n')
            append("电池白名单：").append(
                when (batteryOk) {
                    true -> "已加入"
                    false -> "未加入"
                    null -> "未知"
                }
            ).append('\n')
            append("目标 WiFi：").append(target).append('\n')
            append("重连间隔：").append(prefs.intervalSec).append(" 秒\n")
            append("WiFi 开关：").append(if (wifiOn) "开" else "关").append('\n')
            append("当前连接：").append(current).append('\n')
            append("是否已连目标：").append(if (matched) "是" else "否")
            if (!extra.isNullOrBlank()) {
                append("\n最近：").append(extra)
            }
        }
    }

    /** true=已不优化；false=仍被优化；null=系统不支持检测 */
    private fun isIgnoringBatteryOptimizations(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val pm = getSystemService(PowerManager::class.java) ?: return null
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryStatus() {
        when (isIgnoringBatteryOptimizations()) {
            true -> {
                binding.textBattery.text = getString(R.string.battery_ok)
                binding.btnBattery.text = getString(R.string.battery_open_list)
            }
            false -> {
                binding.textBattery.text = getString(R.string.battery_need)
                binding.btnBattery.text = getString(R.string.battery_set)
            }
            null -> {
                binding.textBattery.text = getString(R.string.battery_unsupported)
                binding.btnBattery.text = getString(R.string.battery_open_list)
            }
        }
    }

    private fun openBatteryWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && isIgnoringBatteryOptimizations() == false
            ) {
                // 弹系统「允许忽略电池优化」对话框
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                // 已加入或无法检测：打开系统电池优化列表，方便核对
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.battery_unsupported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_LOCATION = 100
    }
}
