package com.s20.remote.core

import android.content.Context
import android.content.SharedPreferences

/** 全局配置（SharedPreferences 持久化，可由远程 config.set 指令修改） */
object Config {
    private lateinit var sp: SharedPreferences
    private lateinit var appCtx: Context

    const val DEFAULT_HTTP_PORT = 8999
    const val DEFAULT_RELAY_URL = "ws://your.vps.ip:9000/ws"

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // Direct Boot：配置存设备保护(DE)区，锁屏未解锁时也可读写（API 23 降级为 CE 区，见 Compat）
        sp = com.s20.remote.core.Compat.deContext(appCtx)
            .getSharedPreferences("s20_remote", Context.MODE_PRIVATE)
    }

    /**
     * 用户解锁后，把历史遗留的 CE 区配置迁移到 DE 区（仅当 DE 为空时）。
     * 由 BootReceiver 收到 USER_UNLOCKED 时调用，保证旧配置不丢失。
     */
    fun migrateFromCredentialStorage() {
        try {
            // 反射调用 createCredentialProtectedStorageContext（兼容低版本编译桩）
            val m = Context::class.java.getMethod("createCredentialProtectedStorageContext")
            val ceCtx = m.invoke(appCtx) as Context
            val ce = ceCtx.getSharedPreferences("s20_remote", Context.MODE_PRIVATE)
            val ceAll = ce.all
            if (ceAll.isNotEmpty() && sp.all.isEmpty()) {
                val ed = sp.edit()
                for ((k, v) in ceAll) {
                    when (v) {
                        is String -> ed.putString(k, v)
                        is Int -> ed.putInt(k, v)
                        is Boolean -> ed.putBoolean(k, v)
                        is Long -> ed.putLong(k, v)
                        is Float -> ed.putFloat(k, v)
                    }
                }
                ed.apply()
                android.util.Log.i("Config", "migrated ${ceAll.size} config keys from CE to DE")
            }
        } catch (e: Exception) {
            android.util.Log.w("Config", "migrate CE->DE failed: ${e.message}")
        }
    }

    var token: String
        get() = sp.getString("token", "s20-default-token")!!
        set(v) = sp.edit().putString("token", v).apply()

    var httpPort: Int
        get() = sp.getInt("httpPort", DEFAULT_HTTP_PORT)
        set(v) = sp.edit().putInt("httpPort", v).apply()

    var relayUrl: String
        get() = sp.getString("relayUrl", DEFAULT_RELAY_URL)!!
        set(v) = sp.edit().putString("relayUrl", v).apply()

    var relayEnabled: Boolean
        get() = sp.getBoolean("relayEnabled", false)
        set(v) = sp.edit().putBoolean("relayEnabled", v).apply()

    var ddnsProvider: String
        get() = sp.getString("ddnsProvider", "dnsexit")!!
        set(v) = sp.edit().putString("ddnsProvider", v).apply()

    var ddnsUser: String
        get() = sp.getString("ddnsUser", "")!!
        set(v) = sp.edit().putString("ddnsUser", v).apply()

    var ddnsPass: String
        get() = sp.getString("ddnsPass", "")!!
        set(v) = sp.edit().putString("ddnsPass", v).apply()

    var ddnsHost: String
        get() = sp.getString("ddnsHost", "")!!
        set(v) = sp.edit().putString("ddnsHost", v).apply()

    /** IP 类型：ipv4 / ipv6 / all */
    var ddnsIpType: String
        get() = sp.getString("ddnsIpType", "ipv6")!!
        set(v) = sp.edit().putString("ddnsIpType", v).apply()

    /** 更新间隔（分钟），默认 6 小时 = 360 */
    var ddnsIntervalMin: Int
        get() = sp.getInt("ddnsIntervalMin", 360)
        set(v) = sp.edit().putInt("ddnsIntervalMin", v).apply()

    var logEnabled: Boolean
        get() = sp.getBoolean("logEnabled", false)
        set(v) = sp.edit().putBoolean("logEnabled", v).apply()

    /** 日志落盘路径：内部存储 logs 目录 */
    var logFile: String
        get() = sp.getString("logFile", "/sdcard/logs/s20remote.log")!!
        set(v) = sp.edit().putString("logFile", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", "s20")!!
        set(v) = sp.edit().putString("deviceName", v).apply()

    // ===== 状态上报/告警 =====
    /** 心跳间隔（秒） */
    var heartbeatSec: Int
        get() = sp.getInt("heartbeatSec", 60)
        set(v) = sp.edit().putInt("heartbeatSec", v).apply()

    /** 电量低于该百分比报警 */
    var batteryWarnPct: Int
        get() = sp.getInt("batteryWarnPct", 65)
        set(v) = sp.edit().putInt("batteryWarnPct", v).apply()

    /** 电池温度报警阈值（℃） */
    var tempWarnC: Int
        get() = sp.getInt("tempWarnC", 40)
        set(v) = sp.edit().putInt("tempWarnC", v).apply()

    /** CPU 温度报警阈值（℃） */
    var cpuWarnC: Int
        get() = sp.getInt("cpuWarnC", 60)
        set(v) = sp.edit().putInt("cpuWarnC", v).apply()

    /** 可用存储低于该值（GB）报警 */
    var storageWarnGb: Int
        get() = sp.getInt("storageWarnGb", 10)
        set(v) = sp.edit().putInt("storageWarnGb", v).apply()

    // ===== HTTPS / TLS =====
    /** 是否启用 HTTPS（关闭后回退明文，仅调试用） */
    var tlsEnabled: Boolean
        get() = sp.getBoolean("tlsEnabled", true)
        set(v) = sp.edit().putBoolean("tlsEnabled", v).apply()

    /** 证书模式：selfsigned=自动生成 / import=已导入 */
    var tlsMode: String
        get() = sp.getString("tlsMode", "selfsigned")!!
        set(v) = sp.edit().putString("tlsMode", v).apply()

    /** PKCS12 密钥库口令（首次生成时随机写入，不对外回传） */
    var tlsStorePass: String
        get() = sp.getString("tlsStorePass", "")!!
        set(v) = sp.edit().putString("tlsStorePass", v).apply()

    /** 中转 wss 是否跳过证书校验（自签中转时使用，默认开启） */
    var relayInsecureTls: Boolean
        get() = sp.getBoolean("relayInsecureTls", true)
        set(v) = sp.edit().putBoolean("relayInsecureTls", v).apply()

    // ===== 数据流量监控 =====
    var trafficEnabled: Boolean
        get() = sp.getBoolean("trafficEnabled", false)
        set(v) = sp.edit().putBoolean("trafficEnabled", v).apply()

    /** 查询方式：sms=发短信给运营商查询 / cmd=本地 shell 命令 */
    var trafficQueryMode: String
        get() = sp.getString("trafficQueryMode", "sms")!!
        set(v) = sp.edit().putString("trafficQueryMode", v).apply()

    /** 运营商查询号码 */
    var trafficQueryNumber: String
        get() = sp.getString("trafficQueryNumber", "10086")!!
        set(v) = sp.edit().putString("trafficQueryNumber", v).apply()

    /** 查询命令文本（短信内容，如 CXLL） */
    var trafficQueryCmd: String
        get() = sp.getString("trafficQueryCmd", "CXLL")!!
        set(v) = sp.edit().putString("trafficQueryCmd", v).apply()

    /** 本地查询命令（输出已用流量 MB） */
    var trafficLocalCmd: String
        get() = sp.getString("trafficLocalCmd", "")!!
        set(v) = sp.edit().putString("trafficLocalCmd", v).apply()

    /** 报警阈值（MB） */
    var trafficWarnMb: Int
        get() = sp.getInt("trafficWarnMb", 3000)
        set(v) = sp.edit().putInt("trafficWarnMb", v).apply()

    /** 报警短信接收号码 */
    var trafficAlertNumber: String
        get() = sp.getString("trafficAlertNumber", "")!!
        set(v) = sp.edit().putString("trafficAlertNumber", v).apply()

    /** 检查间隔（分钟），默认 24 小时 */
    var trafficCheckMin: Int
        get() = sp.getInt("trafficCheckMin", 1440)
        set(v) = sp.edit().putInt("trafficCheckMin", v).apply()

    /** 上次流量告警日期（去重，yyyy-MM-dd） */
    var trafficAlertDay: String
        get() = sp.getString("trafficAlertDay", "")!!
        set(v) = sp.edit().putString("trafficAlertDay", v).apply()

    /** 最近一次已用流量（MB，-1=未知） */
    var trafficUsedMb: Int
        get() = sp.getInt("trafficUsedMb", -1)
        set(v) = sp.edit().putInt("trafficUsedMb", v).apply()

    /** 上次流量检查时间戳（ms） */
    var trafficLastCheckAt: Long
        get() = sp.getLong("trafficLastCheckAt", 0L)
        set(v) = sp.edit().putLong("trafficLastCheckAt", v).apply()

    // ===== 唤醒解锁 =====
    /** 唤醒时若屏幕锁定，是否尝试自动解锁（与开机脚本同一套方式） */
    var unlockEnabled: Boolean
        get() = sp.getBoolean("unlockEnabled", true)
        set(v) = sp.edit().putBoolean("unlockEnabled", v).apply()

    /** 锁屏 PIN（明文存 DE 区，仅 config.get 不回显；与 magisk service.sh 保持一致） */
    var unlockPin: String
        get() = sp.getString("unlockPin", "000000")!!
        set(v) = sp.edit().putString("unlockPin", v).apply()

    // ===== 开关自动恢复（心跳/守卫检测到关闭则自动打开）=====
    var autoRestoreWifi: Boolean
        get() = sp.getBoolean("autoRestoreWifi", false)
        set(v) = sp.edit().putBoolean("autoRestoreWifi", v).apply()

    var autoRestoreHotspot: Boolean
        get() = sp.getBoolean("autoRestoreHotspot", false)
        set(v) = sp.edit().putBoolean("autoRestoreHotspot", v).apply()

    var autoRestoreData: Boolean
        get() = sp.getBoolean("autoRestoreData", false)
        set(v) = sp.edit().putBoolean("autoRestoreData", v).apply()

    var autoRestoreBluetooth: Boolean
        get() = sp.getBoolean("autoRestoreBluetooth", false)
        set(v) = sp.edit().putBoolean("autoRestoreBluetooth", v).apply()

    var autoRestoreLocation: Boolean
        get() = sp.getBoolean("autoRestoreLocation", false)
        set(v) = sp.edit().putBoolean("autoRestoreLocation", v).apply()

    /** 自动恢复检查间隔（秒），默认 300，与中转连接状态无关 */
    /** 调试：强制按“无 root”分支执行（用于验证降级路径，仅限本机调试） */
    var simulateNoRoot: Boolean
        get() = sp.getBoolean("simulateNoRoot", false)
        set(v) = sp.edit().putBoolean("simulateNoRoot", v).apply()

    /** App 前台空闲自动熄屏（首页「功能」里的开关，默认关） */
    var appIdleScreenOff: Boolean
        get() = sp.getBoolean("appIdleScreenOff", false)
        set(v) = sp.edit().putBoolean("appIdleScreenOff", v).apply()

    var guardSec: Int
        get() = sp.getInt("guardSec", 300)
        set(v) = sp.edit().putInt("guardSec", v.coerceIn(30, 600)).apply()

    fun autoRestoreOf(key: String): Boolean = when (key) {
        "wifi" -> autoRestoreWifi
        "hotspot" -> autoRestoreHotspot
        "data" -> autoRestoreData
        "bluetooth" -> autoRestoreBluetooth
        "location" -> autoRestoreLocation
        else -> false
    }

    fun dump(): org.json.JSONObject = org.json.JSONObject().apply {
        put("token", token); put("httpPort", httpPort); put("relayUrl", relayUrl)
        put("relayEnabled", relayEnabled)
        put("ddnsProvider", ddnsProvider); put("ddnsUser", ddnsUser); put("ddnsHost", ddnsHost)
        put("ddnsIpType", ddnsIpType); put("ddnsIntervalMin", ddnsIntervalMin)
        put("logEnabled", logEnabled); put("logFile", logFile)
        put("deviceName", deviceName)
        put("heartbeatSec", heartbeatSec); put("batteryWarnPct", batteryWarnPct)
        put("tempWarnC", tempWarnC); put("cpuWarnC", cpuWarnC)
        put("storageWarnGb", storageWarnGb)
        put("tlsEnabled", tlsEnabled); put("tlsMode", tlsMode)
        put("relayInsecureTls", relayInsecureTls)
        put("tlsStorePassSet", tlsStorePass.isNotEmpty())
        put("trafficEnabled", trafficEnabled); put("trafficQueryMode", trafficQueryMode)
        put("trafficQueryNumber", trafficQueryNumber); put("trafficQueryCmd", trafficQueryCmd)
        put("trafficLocalCmd", trafficLocalCmd); put("trafficWarnMb", trafficWarnMb)
        put("trafficAlertNumber", trafficAlertNumber); put("trafficCheckMin", trafficCheckMin)
        put("trafficUsedMb", trafficUsedMb); put("trafficAlertDay", trafficAlertDay)
        put("unlockEnabled", unlockEnabled); put("unlockPinSet", unlockPin.isNotEmpty())
        put("autoRestoreWifi", autoRestoreWifi); put("autoRestoreHotspot", autoRestoreHotspot)
        put("autoRestoreData", autoRestoreData); put("autoRestoreBluetooth", autoRestoreBluetooth)
        put("autoRestoreLocation", autoRestoreLocation); put("guardSec", guardSec)
        put("appIdleScreenOff", appIdleScreenOff)
        put("simulateNoRoot", simulateNoRoot)
        // 密码不回传明文，仅告知是否已设置
        put("ddnsPassSet", ddnsPass.isNotEmpty())
    }

    fun setJson(key: String, value: Any?): Boolean {
        try {
            when (key) {
                "token" -> token = value.toString()
                "httpPort" -> httpPort = (value as Number).toInt()
                "relayUrl" -> relayUrl = value.toString()
                "relayEnabled" -> relayEnabled = value as Boolean
                "ddnsProvider" -> ddnsProvider = value.toString()
                "ddnsUser" -> ddnsUser = value.toString()
                "ddnsPass" -> value.toString().let { if (it.isNotEmpty()) ddnsPass = it }
                "ddnsHost" -> ddnsHost = value.toString()
                "ddnsIpType" -> {
                    val v = value.toString()
                    ddnsIpType = if (v in listOf("ipv4", "ipv6", "all")) v else "ipv6"
                }
                "ddnsIntervalMin" -> ddnsIntervalMin = (value as Number).toInt()
                "logEnabled" -> logEnabled = value as Boolean
                "logFile" -> logFile = value.toString()
                "deviceName" -> deviceName = value.toString()
                "heartbeatSec" -> heartbeatSec = (value as Number).toInt().coerceIn(10, 3600)
                "batteryWarnPct" -> batteryWarnPct = (value as Number).toInt().coerceIn(1, 100)
                "tempWarnC" -> tempWarnC = (value as Number).toInt().coerceIn(20, 90)
                "cpuWarnC" -> cpuWarnC = (value as Number).toInt().coerceIn(20, 120)
                "storageWarnGb" -> storageWarnGb = (value as Number).toInt().coerceIn(1, 512)
                "tlsEnabled" -> tlsEnabled = value as Boolean
                "tlsMode" -> tlsMode = if (value.toString() == "import") "import" else "selfsigned"
                "relayInsecureTls" -> relayInsecureTls = value as Boolean
                "trafficEnabled" -> trafficEnabled = value as Boolean
                "trafficQueryMode" -> trafficQueryMode = if (value.toString() == "cmd") "cmd" else "sms"
                "trafficQueryNumber" -> trafficQueryNumber = value.toString()
                "trafficQueryCmd" -> trafficQueryCmd = value.toString()
                "trafficLocalCmd" -> trafficLocalCmd = value.toString()
                "trafficWarnMb" -> trafficWarnMb = (value as Number).toInt().coerceAtLeast(1)
                "trafficAlertNumber" -> trafficAlertNumber = value.toString()
                "trafficCheckMin" -> trafficCheckMin = (value as Number).toInt().coerceIn(10, 10080)
                "trafficUsedMb" -> trafficUsedMb = (value as Number).toInt()
                "trafficAlertDay" -> trafficAlertDay = value.toString()
                "unlockEnabled" -> unlockEnabled = value as Boolean
                "unlockPin" -> unlockPin = value.toString().trim()
                "autoRestoreWifi" -> autoRestoreWifi = value as Boolean
                "autoRestoreHotspot" -> autoRestoreHotspot = value as Boolean
                "autoRestoreData" -> autoRestoreData = value as Boolean
                "autoRestoreBluetooth" -> autoRestoreBluetooth = value as Boolean
                "autoRestoreLocation" -> autoRestoreLocation = value as Boolean
                "guardSec" -> guardSec = (value as Number).toInt()
                "appIdleScreenOff" -> appIdleScreenOff = value as Boolean
                "simulateNoRoot" -> simulateNoRoot = value as Boolean
                else -> return false
            }
            return true
        } catch (e: Exception) { return false }
    }
}
