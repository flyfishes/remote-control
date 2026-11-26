package com.s20.remote.modules

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import com.s20.remote.core.Capabilities
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONObject

/**
 * 固定参数开关：WiFi / 移动数据 / 蓝牙 / 定位（热点复用 HotspotModule）。
 * 真机验证过的命令（SM-G9810 / Android 13，root）：
 *  - WiFi:   cmd wifi set-wifi-enabled enabled|disabled
 *  - 数据:   svc data enable|disable（svc help 未列出但可用）
 *  - 蓝牙:   cmd bluetooth_manager enable|disable
 *  - 定位:   settings put secure location_mode 3|0（LocationManagerService 监听该设置，即时生效）
 *
 * 双路径：
 *  - 读状态：全部走公共 API（无 root 也能拿到真实状态），root 失败时逐项回退 shell
 *  - 写开关：root 可用走原命令；无 root 时
 *      WiFi  <=28 WifiManager.setWifiEnabled（29+ 系统不再允许第三方开关）
 *      蓝牙  <=32 BluetoothAdapter.enable/disable（33+ 系统不再允许）
 *      数据/定位  无公共 API，返回 NEED_ROOT
 */
object SwitchModule {
    private const val TAG = "Switch"

    private lateinit var appCtx: Context
    private val sdk get() = android.os.Build.VERSION.SDK_INT

    fun register(ctx: Context) {
        appCtx = ctx.applicationContext
        CommandRouter.register("switch.get") { get(ctx) }
        CommandRouter.register("wifi.set") { p -> setWifi(p) }
        CommandRouter.register("bluetooth.set") { p -> setBt(p) }
        CommandRouter.register("location.set") { p -> setLoc(p) }
    }

    /** 一次性返回 5 项开关状态（读：公共 API，任何设备/权限下都真实） */
    fun get(ctx: Context): JSONObject {
        val o = JSONObject()
        // WiFi
        o.put("wifi", try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        } catch (e: Exception) {
            RootShell.execSoft("settings get global wifi_on").stdout.trim() == "1"
        })
        // 移动数据
        o.put("data", try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (sdk >= 26) tm.isDataEnabled
            else RootShell.execSoft("settings get global mobile_data").stdout.trim() == "1"
        } catch (e: Exception) { false })
        // 蓝牙
        o.put("bluetooth", try {
            val ad = BluetoothAdapter.getDefaultAdapter()
            ad?.isEnabled ?: false
        } catch (e: Exception) {
            // 31+ 无 BLUETOOTH_CONNECT 时读 isEnabled 抛 SecurityException
            RootShell.execSoft("settings get global bluetooth_on").stdout.trim() == "1"
        })
        // 定位
        o.put("location", try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (sdk >= 28) lm.isLocationEnabled
            else (android.provider.Settings.Secure.getInt(
                ctx.contentResolver, android.provider.Settings.Secure.LOCATION_MODE, 0) > 0)
        } catch (e: Exception) { false })
        o.put("hotspot", HotspotModule.quickState(ctx))
        return o
    }

    // ---------- 写开关 ----------

    fun setWifi(p: JSONObject): JSONObject {
        val on = p.optBoolean("on", false)
        val feat = "wifi.set"
        if (RootShell.available()) {
            return applySwitch { RootShell.exec("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}", 15000) }
        }
        if (sdk <= 28) {
            return try {
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ok = wm.setWifiEnabled(on)
                val r = JSONObject().put("ok", ok).put("mode", "public-api")
                if (!ok) r.put("hint", "系统拒绝第三方修改 WiFi（Android 9 以下有限支持）")
                r
            } catch (e: Exception) {
                Capabilities.needRoot(feat)
            }
        }
        return Capabilities.needRoot(feat)
    }

    fun setBt(p: JSONObject): JSONObject {
        val on = p.optBoolean("on", false)
        val feat = "bluetooth.set"
        if (RootShell.available()) {
            return applySwitch { RootShell.exec("cmd bluetooth_manager ${if (on) "enable" else "disable"}", 15000) }
        }
        if (sdk <= 32) {
            if (sdk >= 31 && !Capabilities.hasPerm(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                return Capabilities.needPerm("需要蓝牙连接权限（BLUETOOTH_CONNECT），请在系统设置授权", feat)
            }
            return try {
                val ad = BluetoothAdapter.getDefaultAdapter()
                    ?: return Capabilities.notSupported("设备无蓝牙", feat)
                val ok = if (on) ad.enable() else ad.disable()
                val r = JSONObject().put("ok", ok).put("mode", "public-api")
                if (!ok) r.put("hint", "系统拒绝第三方修改蓝牙，或权限不足")
                r
            } catch (e: SecurityException) {
                Capabilities.needPerm("需要蓝牙权限，请在系统设置授权", feat)
            } catch (e: Exception) {
                Capabilities.needRoot(feat)
            }
        }
        return Capabilities.needRoot(feat)
    }

    fun setLoc(p: JSONObject): JSONObject {
        return if (RootShell.available()) {
            applySwitch {
                RootShell.exec("settings put secure location_mode ${if (p.optBoolean("on", false)) "3" else "0"}")
            }
        } else {
            Capabilities.needRoot("location.set")
        }
    }

    /** 执行开关命令后回读确认真实状态 */
    private fun applySwitch(exec: () -> com.s20.remote.core.ShellResult): JSONObject {
        val o = JSONObject()
        try {
            val r = exec()
            o.put("ok", r.ok)
            if (!r.ok) o.put("error", r.stderr.ifEmpty { r.stdout }.take(200))
        } catch (e: Exception) {
            o.put("ok", false).put("error", e.message ?: e.toString())
        }
        return o
    }
}
