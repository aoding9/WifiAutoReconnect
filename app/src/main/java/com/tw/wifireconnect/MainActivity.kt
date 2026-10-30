package com.tw.wifireconnect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
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
        binding.btnTest.setOnClickListener {
            saveSettings(showToast = false)
            if (prefs.targetSsid.isEmpty()) {
                Toast.makeText(this, R.string.hint_ssid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = WifiHelper.connectSaved(this, prefs.targetSsid)
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
            // 仅改 UI；真正生效点保存
            if (!checked) {
                // 立刻停服务更直观
                prefs.enabled = false
                WifiReconnectService.stop(this)
                refreshStatus("已停止服务")
            }
        }

        ensurePermissions()
        loadWifiList(false)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(WifiReconnectService.ACTION_STATUS_CHANGED))
        refreshStatus()
    }

    override fun onPause() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
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

    private fun saveSettings(showToast: Boolean = true) {
        val intervalText = binding.editInterval.text?.toString()?.trim().orEmpty()
        val interval = intervalText.toIntOrNull()
        if (interval == null || interval < AppPrefs.MIN_INTERVAL || interval > AppPrefs.MAX_INTERVAL) {
            Toast.makeText(this, R.string.interval_invalid, Toast.LENGTH_SHORT).show()
            return
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
            return
        }

        prefs.intervalSec = interval
        prefs.bootStart = binding.switchBoot.isChecked
        prefs.targetSsid = ssid
        prefs.enabled = binding.switchEnable.isChecked

        if (prefs.enabled) {
            WifiReconnectService.start(this)
        } else {
            WifiReconnectService.stop(this)
        }

        if (showToast) {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        refreshStatus("设置已保存")
    }

    private fun refreshStatus(extra: String? = null) {
        val wifiOn = WifiHelper.isWifiEnabled(this)
        val current = WifiHelper.currentSsid(this).ifEmpty { "未连接" }
        val target = prefs.targetSsid.ifEmpty { "未选择" }
        val matched = prefs.targetSsid.isNotEmpty() &&
            WifiHelper.isConnectedTo(this, prefs.targetSsid)

        binding.textStatus.text = buildString {
            append("服务：").append(if (prefs.enabled) "运行中" else "未启用").append('\n')
            append("开机自启：").append(if (prefs.bootStart) "是" else "否").append('\n')
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

    companion object {
        private const val REQ_LOCATION = 100
    }
}
