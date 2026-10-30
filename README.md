# WifiAutoReconnect

Android 前台服务：指定 WiFi SSID，断线后按间隔自动重连，支持开机自启。适合展厅、评分、工控等长期在线的平板场景。

> Android foreground service that auto-reconnects to a chosen WiFi SSID on disconnect. Boot start + configurable interval. Aimed at kiosk / industrial tablets.

## 功能

- 从已保存网络中选择**目标 WiFi**
- 断线监听 + 定时轮询，按间隔尝试重连（默认 3 秒，可设 3～3600 秒）
- 前台服务常驻（通知栏可见）
- 可选开机自启
- 支持「立即重连一次」手动测试

## 环境要求

| 项 | 说明 |
|---|---|
| 语言 | Kotlin |
| minSdk | 23 |
| targetSdk | 28 |
| 构建 | Android Studio / JDK 17 |

> **为何 targetSdk 28？** Android 10（API 29）起，普通应用无法再可靠地开关 / 强制连接已保存 WiFi。本工具面向可控设备（平板可降低目标 API 或使用专用 ROM），刻意保持 28 以保留 `CHANGE_WIFI_STATE` 等能力。

## 使用

1. 安装 APK 或用 Android Studio 运行 `app` 模块  
2. 授予定位权限（扫描 / 读取 WiFi 列表需要）  
3. 选择目标 WiFi，设置重连间隔  
4. 打开「启用自动重连」；需要的话打开「开机自启」  
5. 可点「立即重连一次」验证

厂商省电策略可能杀掉后台：请在系统设置里允许本应用自启 / 忽略电池优化。

## 构建

```bash
./gradlew assembleDebug
# Windows:
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 权限说明

- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`：读写与切换 WiFi  
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`：获取 SSID / 扫描列表（系统要求）  
- `FOREGROUND_SERVICE`：前台服务保活  
- `RECEIVE_BOOT_COMPLETED`：开机自启  
- `WAKE_LOCK`：重连流程短暂唤醒  

## 限制

- 目标 WiFi 须已在系统里保存过（本应用不负责输入密码配网）  
- 高版本系统 / 严格厂商 ROM 上，能力可能受限  
- 不保证在所有机型上 100% 保活，需配合系统白名单  

## License

MIT
