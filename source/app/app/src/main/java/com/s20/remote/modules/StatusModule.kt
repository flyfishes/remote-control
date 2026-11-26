package com.s20.remote.modules

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.s20.remote.core.Capabilities
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

/** 手机状态：电量/网络/WiFi/IPv4/IPv6/系统信息 */
object StatusModule {

    fun register(ctx: Context) {
        CommandRouter.register("status.get") { full(ctx) }
        CommandRouter.register("net.ips") { JSONObject().put("ips", ips()) }
        CommandRouter.register("net.publicIp") { p -> publicIp(p.optBoolean("refresh", false)) }
        CommandRouter.register("device.info") { deviceInfo() }
        CommandRouter.register("device.reboot") { rebootDevice() }
        CommandRouter.register("device.screenOff") { screenOff() }
        CommandRouter.register("device.wakeUp") { wakeUp() }
        CommandRouter.register("traffic.check") { com.s20.remote.net.StatusReporter.forceCheck(ctx) }
        CommandRouter.register("storage.get") { storage() }
        CommandRouter.register("cap.get") {
            com.s20.remote.core.Capabilities.probe(it.optBoolean("recheck", false))
        }
    }

    // 公网 IPv4 缓存（外部探测服务有频率限制，默认缓存 60s）
    @Volatile private var cachedPublicIp: String? = null
    @Volatile private var cachedPublicIpAt = 0L

    fun publicIp(refresh: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        if (refresh || cachedPublicIp == null || now - cachedPublicIpAt > 60_000) {
            cachedPublicIp = DdnsModule.detectPublicIpv4()
            cachedPublicIpAt = now
        }
        return JSONObject()
            .put("ipv4", cachedPublicIp ?: JSONObject.NULL)
            .put("ts", cachedPublicIpAt)
    }

    /** CPU 温度（℃）：取 thermal_zone 中 cpu/apc/soc/gpu 热区最大值，失败返回 -1 */
    fun cpuTempC(): Double {
        var max = -1.0
        try {
            File("/sys/class/thermal").listFiles()?.forEach { z ->
                if (!z.name.startsWith("thermal_zone")) return@forEach
                val type = try { File(z, "type").readText().trim().lowercase() } catch (e: Exception) { return@forEach }
                if (!(type.contains("cpu") || type.startsWith("apc") || type.contains("soc") || type.contains("gpu"))) return@forEach
                val raw = try { File(z, "temp").readText().trim().toDoubleOrNull() } catch (e: Exception) { null } ?: return@forEach
                val c = if (raw > 1000) raw / 1000.0 else raw
                if (c in 1.0..150.0 && c > max) max = c
            }
        } catch (e: Exception) { }
        if (max < 0) {
            // root 回退（execSoft：shell 忙则跳过，不阻塞心跳）
            val r = RootShell.execSoft("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null", 8000)
            r.stdout.lineSequence().mapNotNull { it.trim().toDoubleOrNull() }.forEach { raw ->
                val c = if (raw > 1000) raw / 1000.0 else raw
                if (c in 1.0..150.0 && c > max) max = c
            }
        }
        if (max < 0) {
            // 最终兜底：电池温度（sticky 广播，任何机型可用，量级接近整机温度）
            try {
                val intent = appCtx()?.registerReceiver(null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                if (t in 100..800) max = t / 10.0
            } catch (_: Exception) {}
        }
        return max
    }

    private fun appCtx(): Context? = try { com.s20.remote.App.ctx } catch (_: Exception) { null }

    /**
     * 内部存储（/data，含模拟存储）容量信息。
     * freeGb < Config.storageWarnGb 时 low=true，作为异常上报依据。
     */
    fun storage(): JSONObject {
        val o = JSONObject()
        return try {
            val sf = StatFs(Environment.getDataDirectory().absolutePath)
            val total = sf.totalBytes
            val free = sf.availableBytes
            val totalGb = total.toDouble() / 1024 / 1024 / 1024
            val freeGb = free.toDouble() / 1024 / 1024 / 1024
            val usedPct = if (total > 0) (total - free) * 100.0 / total else 0.0
            o.put("totalGb", "%.1f".format(totalGb).toDouble())
            o.put("freeGb", "%.1f".format(freeGb).toDouble())
            o.put("usedPct", "%.1f".format(usedPct).toDouble())
            o.put("freeBytes", free)
            o.put("warnGb", com.s20.remote.core.Config.storageWarnGb)
            o.put("low", freeGb < com.s20.remote.core.Config.storageWarnGb)
            o.put("path", Environment.getDataDirectory().absolutePath)
        } catch (e: Exception) {
            Logger.w("Status", "storage: ${e.message}")
            o.put("error", e.message ?: e.toString())
            o.put("warnGb", com.s20.remote.core.Config.storageWarnGb)
            o.put("low", false)
        }
        o
    }

    /** 重启手机：不等待命令返回（reboot 会立即中断连接） */
    /**
     * 熄屏：优先 KEYCODE_SLEEP(223)，部分 ROM 不生效则回退电源键(26)。
     * 执行后回读 mWakefulness 确认，避免"假成功"。
     */
    /** 供 App 内部（如首页空闲自动熄屏）直接调用 */
    fun screenOffNow(): JSONObject = screenOff()

    private fun screenOff(): JSONObject {
        // 无 root：设备管理员 lockNow 兜底（需在 App 内激活一次设备管理员）
        if (!RootShell.available()) {
            return screenOffByAdmin()
        }
        return try {
            var method = "keyevent 223"
            com.s20.remote.core.RootShell.exec("input keyevent 223", 8000)
            Thread.sleep(400)
            var w = wakefulness()
            if (w.contains("Awake")) {
                com.s20.remote.core.RootShell.exec("input keyevent 26", 8000)
                method = "keyevent 26"
                Thread.sleep(400)
                w = wakefulness()
            }
            val asleep = w.contains("Asleep") || w.contains("Dozing")
            Logger.i("Status", "screenOff via $method -> $w")
            JSONObject().put("ok", asleep).put("method", method).put("wakefulness", w)
                .put("msg", if (asleep) "已熄屏" else "已下发，但屏幕仍亮（$method）")
        } catch (e: Exception) {
            Logger.w("Status", "screenOff: ${e.message}")
            screenOffByAdmin()
        }
    }

    /** 无 root 熄屏：设备管理员 DevicePolicyManager.lockNow() */
    private fun screenOffByAdmin(): JSONObject {
        val feat = "device.screenOff"
        return try {
            val dpm = appCtx()?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(appCtx()!!, com.s20.remote.service.AdminReceiver::class.java)
            if (dpm?.isAdminActive(admin) == true) {
                dpm.lockNow()
                Logger.i("Status", "screenOff via device admin lockNow")
                JSONObject().put("ok", true).put("method", "device-admin")
                    .put("msg", "已熄屏（设备管理员）")
            } else {
                Capabilities.needPerm("无 root 时熄屏需先激活设备管理员：打开 App → 能力面板 → 激活", feat)
            }
        } catch (e: Exception) {
            Capabilities.needRoot(feat)
        }
    }

    /** 唤醒：KEYCODE_WAKEUP(224) 点亮；若仍锁定则按开机脚本同一套方式解锁 */
    private fun wakeUp(): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("device.wakeUp")
        return try {
            com.s20.remote.core.RootShell.exec("input keyevent 224", 8000)
            Thread.sleep(400)
            val w = wakefulness()
            val awake = w.contains("Awake")
            val r = JSONObject().put("ok", awake).put("wakefulness", w)
            if (!awake) {
                return r.put("locked", isLocked()).put("msg", "已下发，屏幕未点亮")
            }
            if (!isLocked()) {
                return r.put("locked", false).put("unlocked", false).put("msg", "已点亮（未锁定）")
            }
            // 锁屏 → 自动解锁
            if (!com.s20.remote.core.Config.unlockEnabled) {
                return r.put("locked", true).put("unlocked", false)
                    .put("msg", "已点亮屏幕（锁屏；自动解锁已关闭）")
            }
            if (com.s20.remote.core.Config.unlockPin.isEmpty()) {
                return r.put("locked", true).put("unlocked", false)
                    .put("msg", "已点亮屏幕（锁屏；未配置解锁 PIN）")
            }
            val u = unlockLocked(3)
            r.put("locked", u.first).put("unlocked", !u.first)
                .put("attempts", u.second).put("wakefulness", wakefulness())
                .put("msg", if (!u.first) "已点亮并解锁" else "已点亮，解锁失败（尝试 ${u.second} 次）")
        } catch (e: Exception) {
            Logger.w("Status", "wakeUp: ${e.message}")
            JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        }
    }

    /** 是否处于锁屏：KeyguardManager（公共 API，全机型可用）优先，root dumpsys 回退 */
    fun isLocked(): Boolean {
        try {
            val km = appCtx()?.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (km != null) return km.isKeyguardLocked
        } catch (_: Exception) {}
        return try {
            com.s20.remote.core.RootShell.exec("dumpsys trust | grep -m1 deviceLocked=", 8000)
                .stdout.contains("deviceLocked=1")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确保屏幕已解锁（供媒体模块复用）：未锁定直接通过；
     * 锁定则点亮 → 上滑 → 输 PIN，最多 3 轮。
     * @return true=已解锁（或解锁成功）
     */
    fun ensureUnlocked(): Boolean {
        if (!isLocked()) return true
        val (still, _) = unlockLocked(3)
        return !still
    }

    /**
     * 解锁锁屏：点亮 → 上滑出 PIN 键盘 → 输入 PIN，最多 rounds 轮。
     * 坐标按当前屏幕尺寸实时计算（不再硬编码 1080x2400）。
     * @return Pair(是否仍锁定, 尝试轮数)
     */
    private fun unlockLocked(rounds: Int = 3): Pair<Boolean, Int> {
        val pin = com.s20.remote.core.Config.unlockPin
        var i = 0
        while (i < rounds) {
            if (!isLocked()) return false to i
            i++
            com.s20.remote.core.RootShell.exec("input keyevent 26", 8000)   // 点亮
            Thread.sleep(600)
            val (sw, sh) = screenSize()
            // 从屏幕下部上滑到中部，任意分辨率/纵横比都有效
            com.s20.remote.core.RootShell.exec(
                "input swipe ${sw / 2} ${(sh * 0.83).toInt()} ${sw / 2} ${(sh * 0.33).toInt()} 300", 10000)
            Thread.sleep(700)
            com.s20.remote.core.RootShell.exec("input text $pin", 10000)
            Thread.sleep(1800)
            if (!isLocked()) {
                Logger.i("Status", "unlocked after $i attempt(s)")
                return false to i
            }
        }
        Logger.w("Status", "unlock failed after $i attempt(s)")
        return true to i
    }

    /** 当前屏幕状态：Awake / Dozing / Asleep（root 失败时退化为公共 API 两态） */
    fun wakefulness(): String {
        if (RootShell.available()) {
            val w = com.s20.remote.core.RootShell.execSoft("dumpsys power | grep -m1 mWakefulness=", 6000)
                .stdout.substringAfter("=", "").lineSequence().firstOrNull().orEmpty().trim()
            if (w.isNotEmpty()) return w
        }
        return try {
            val pm = appCtx()?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive == true) "Awake" else "Asleep"
        } catch (_: Exception) { "Unknown" }
    }

    /** 屏幕尺寸（像素），返回 (短边, 长边)——竖屏语义，用于生成任意机型可用的 input swipe 坐标 */
    private fun screenSize(): Pair<Int, Int> = try {
        val dm = appCtx()!!.resources.displayMetrics
        minOf(dm.widthPixels, dm.heightPixels) to maxOf(dm.widthPixels, dm.heightPixels)
    } catch (_: Exception) { 1080 to 2400 }

    fun rebootDevice(): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("device.reboot")
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            Logger.i("Status", "reboot issued")
            return JSONObject().put("ok", true).put("msg", "rebooting")
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        }
    }

    fun full(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("device", deviceInfo())
        o.put("battery", battery(ctx))
        o.put("network", network(ctx))
        o.put("ips", ips())
        o.put("cpuTemp", cpuTempC())
        o.put("storage", storage())
        return o
    }

    fun deviceInfo(): JSONObject {
        val o = JSONObject()
        o.put("model", Build.MODEL)
        o.put("brand", Build.BRAND)
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("androidVersion", Build.VERSION.RELEASE)
        o.put("sdkInt", Build.VERSION.SDK_INT)
        o.put("buildId", Build.ID)
        o.put("fingerprint", Build.FINGERPRINT)
        o.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
        val r = RootShell.exec("uptime; getprop ro.build.version.oneui")
        o.put("uptime", r.stdout.trim())
        return o
    }

    fun battery(ctx: Context): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val o = JSONObject()
        o.put("level", level)
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL)
        val bs = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        if (bs != null) {
            o.put("plugged", bs.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
            o.put("voltage", bs.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
            o.put("temperature", bs.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            o.put("health", bs.getIntExtra(BatteryManager.EXTRA_HEALTH, 0))
        }
        return o
    }

    fun network(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val o = JSONObject()
        o.put("connected", caps != null)
        o.put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        o.put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        o.put("downMbps", if (caps?.linkDownstreamBandwidthKbps != null) caps.linkDownstreamBandwidthKbps / 1000.0 else 0)
        o.put("upMbps", if (caps?.linkUpstreamBandwidthKbps != null) caps.linkUpstreamBandwidthKbps / 1000.0 else 0)

        val wifiEnabled = RootShell.exec("dumpsys wifi | grep -m1 'Wi-Fi is'").stdout
        o.put("wifiRaw", wifiEnabled.trim())
        val svc = RootShell.exec("svc data; svc wifi").stdout
        o.put("svcRaw", svc.trim())
        o.put("airplane", RootShell.exec("settings get global airplane_mode_on").stdout.trim() == "1")
        return o
    }

    fun ips(): JSONArray {
        val v4 = JSONArray(); val v6 = JSONArray()
        try {
            val itfs = NetworkInterface.getNetworkInterfaces() ?: return JSONArray().apply {
                put(JSONObject().put("v4", v4).put("v6", v6))
            }
            for (ni in itfs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    val entry = JSONObject().put("iface", ni.name)
                        .put("addr", addr.hostAddress)
                    if (addr is Inet4Address) v4.put(entry) else v6.put(entry)
                }
            }
        } catch (e: Exception) { }
        return JSONArray().apply { put(JSONObject().put("v4", v4).put("v6", v6)) }
    }
}
