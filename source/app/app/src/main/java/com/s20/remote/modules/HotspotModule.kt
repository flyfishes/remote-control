package com.s20.remote.modules

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * WiFi 热点管理（Android 13 / OneUI 5）。
 * 隐藏 API 需 TETHER_PRIVILEGED / NETWORK_SETTINGS 特权 ——
 * 本 App 已通过 Magisk 模块安装为 priv-app（见 magisk-module/）。
 * 策略1: TetheringManager.startTethering(TetheringRequest) —— Android 11+ 正确入口
 * 策略2: WifiManager.startTethering 反射回退（Android 10 及以下）
 */
object HotspotModule {
    private const val TAG = "Hotspot"
    private const val TETHERING_WIFI = 0

    fun register(ctx: Context) {
        CommandRouter.register("hotspot.get") { getState(ctx) }
        CommandRouter.register("hotspot.set") { p -> setState(ctx, p) }
        CommandRouter.register("hotspot.config") { p -> setConfig(ctx, p) }
        CommandRouter.register("hotspot.probe") { probe(ctx) }
    }

    private fun wm(ctx: Context): WifiManager =
        ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun getState(ctx: Context): JSONObject {
        val o = JSONObject()
        val r = RootShell.exec("dumpsys wifi 2>/dev/null | grep -im1 -A3 'mCurrentSoftApConfiguration'")
        o.put("raw", r.stdout.trim())
        // apState: 10=disabling 11=disabled 12=enabling 13=enabled 14=failed
        val st = tryReflectState(ctx)
        val num = Regex("\\d+").find(st)?.value
        o.put("apState", when (num) {
            "13" -> "enabled"; "12" -> "enabling"; "10" -> "disabling"
            "14" -> "failed"; "11" -> "disabled"
            else -> st
        })
        o.put("clients", connectedClients())
        Regex("ssid = \"([^\"]*)\"").find(r.stdout)?.let { o.put("currentSsid", it.groupValues[1]) }

        // 通过 uid1000 helper 读取持久化配置，补充 password / ssid / band / channel
        if (helperAvailable()) {
            val h = helperExec("get", "", "", "").optString("output")
            val ssid = Regex("^SSID=(.*)$", RegexOption.MULTILINE).find(h)?.groupValues?.get(1)?.trim()
            val pass = Regex("^PASSWORD=(.*)$", RegexOption.MULTILINE).find(h)?.groupValues?.get(1)?.trim()
            val bands = Regex("Channels = \\{([^}]*)\\}").find(h)?.groupValues?.get(1)?.trim()
            val band = Regex("^BAND=(\\S+)$", RegexOption.MULTILINE).find(h)?.groupValues?.get(1)
            val on = Regex("^isOn=(\\w+)$", RegexOption.MULTILINE).find(h)?.groupValues?.get(1)
            if (!ssid.isNullOrEmpty()) o.put("currentSsid", ssid)
            o.put("password", pass ?: "")
            // 频段：优先 helper 的 getBand()，回退从 channel 推断（>=36 为 5G）
            val bandVal = band ?: bands?.let { ch ->
                val firstNum = Regex("\\d+").find(ch)?.value?.toIntOrNull() ?: 0
                if (firstNum >= 36) "5g" else "2g"
            }
            if (!bandVal.isNullOrEmpty()) o.put("band", bandVal)
            if (!bands.isNullOrEmpty()) o.put("channels", bands)
            if (on != null) o.put("helperOn", on.toBoolean())
        }
        return o
    }

    private fun tryReflectState(ctx: Context): String = try {
        val st = WifiManager::class.java.getMethod("getWifiApState").invoke(wm(ctx))
        st?.toString() ?: "null"
    } catch (e: Exception) {
        "reflect-failed: ${e.message}"
    }

    /** 轻量热点开关状态（仅反射查询，不调用 helper，供 switch.get 聚合用） */
    fun quickState(ctx: Context): Boolean {
        val st = tryReflectState(ctx)
        return Regex("\\d+").find(st)?.value == "13"   // 13 = WIFI_AP_STATE_ENABLED
    }

    /** 当前连接的热点客户端（IP+MAC）。主路：dumpsys tethering 的实时客户端表；
     *  回退：热点接口(swlan0)的邻居表。 */
    fun connectedClients(): org.json.JSONArray {
        val clients = org.json.JSONArray()
        if (!RootShell.available()) return clients  // 客户端表需要 root 读 dumpsys
        val seen = HashSet<String>()
        fun add(ip: String, mac: String) {
            val key = "$ip|$mac"
            if (!seen.add(key)) return
            clients.put(JSONObject().put("ip", ip).put("mac", mac))
        }
        val dump = RootShell.exec(
            "dumpsys tethering 2>/dev/null | grep -oE 'client: /[0-9.]+ \\(([0-9a-f]{2}:){5}[0-9a-f]{2}\\)'", 15000)
        Regex("client: /([\\d.]+) \\(([0-9a-f:]{17})\\)").findAll(dump.stdout).forEach {
            add(it.groupValues[1], it.groupValues[2])
        }
        if (clients.length() == 0) {
            // 接口名因机型/ROM 而异（三星 swlan0、AOSP wlan1/ap0...）：全接口扫描邻居表
            val ne = RootShell.exec("ip neigh show 2>/dev/null", 10000)
            Regex("([\\d.]+) dev (\\S+) lladdr ([0-9a-f:]{17})").findAll(ne.stdout).forEach {
                val dev = it.groupValues[2]
                // 过滤明显不是 AP 侧的接口（蜂窝 rmnet 走 IPv4 路由一般无 lladdr，保留代价低）
                add(it.groupValues[1], it.groupValues[3])
            }
        }
        return clients
    }

    /** 热点配置/开关的前提：root + 能执行 helper（helper dex 存在）；否则给出明确不可用原因 */
    private fun configGuard(feature: String): JSONObject? {
        if (!RootShell.available())
            return com.s20.remote.core.Capabilities.needRoot(feature)
        if (!helperAvailable())
            return com.s20.remote.core.Capabilities.notSupported(
                "热点管理依赖 privileged helper（priv-app 安装的 s20apcfg.dex），当前安装方式不支持", feature)
        return null
    }

    private const val HELPER_DEX = "/system/etc/s20apcfg.dex"
    /** AOSP 通用 helper（Android 6.x 等无 TetheringManager 的设备，如乐视） */
    private const val HELPER_DEX_AOSP = "/system/etc/s20letvh.dex"
    private val SAFE = Regex("^[A-Za-z0-9._-]{1,32}$")

    /** 检测可用的 helper 类型：sem=三星 SemWifiManager 版，aosp=通用 WifiManager 版 */
    private fun helperKind(): String? {
        val r = RootShell.exec(
            "for f in $HELPER_DEX $HELPER_DEX_AOSP; do [ -f \$f ] && echo \$f; done", 10000)
        val first = r.stdout.trim().lines().firstOrNull { it.isNotBlank() } ?: return null
        return if (first == HELPER_DEX) "sem" else "aosp"
    }

    private fun helperAvailable(): Boolean = helperKind() != null

    /** 按 helper 类型组命令（三星/通用两个入口类，输出协议一致） */
    private fun helperExec(mode: String, ssid: String, pass: String, band: String): JSONObject {
        val o = JSONObject()
        val dex = when (helperKind()) {
            "aosp" -> HELPER_DEX_AOSP; else -> HELPER_DEX
        }
        val main = when (helperKind()) {
            "aosp" -> "com.s20.helper.LetvMain"; else -> "com.s20.helper.Main"
        }
        val cmd = "su 1000 -c 'CLASSPATH=$dex app_process / $main " +
                "$mode $ssid $pass $band'"
        val r = RootShell.exec(cmd, 60000)
        o.put("ok", r.ok && r.stdout.contains("FATAL").not())
        o.put("output", r.stdout.trim().take(800))
        if (r.stderr.isNotEmpty()) o.put("stderr", r.stderr.trim().take(300))
        Logger.i(TAG, "helper $mode -> ok=${o.getBoolean("ok")}: ${r.stdout.take(150)}")
        return o
    }

    private fun checkParams(p: JSONObject): Triple<String, String, String> {
        val ssid = p.optString("ssid", "").takeIf { it.isNotEmpty() } ?: ""
        val pass = p.optString("pass", "").takeIf { it.isNotEmpty() } ?: ""
        val band = p.optString("band", "").takeIf { it.isNotEmpty() } ?: ""
        if (ssid.isNotEmpty() && !SAFE.matches(ssid)) throw IllegalArgumentException("SSID 仅允许字母数字._- （1-32位）")
        if (pass.isNotEmpty() && !SAFE.matches(pass)) throw IllegalArgumentException("密码仅允许字母数字._- （1-32位）")
        if (band.isNotEmpty() && band.lowercase() !in listOf("2g", "5g")) throw IllegalArgumentException("band 仅支持 2g/5g")
        return Triple(ssid, pass, band)
    }

    /**
     * 开关热点。
     * on=true: helper start —— 有配置参数时先持久化，再经 TetheringManager(TetheringRequest+SoftApConfiguration) 启动；
     *          无参数时读取持久化配置显式传入（三星对 null 会用默认值）。
     * on=false: helper stop —— TetheringManager.stopTethering。
     */
    fun setState(ctx: Context, p: JSONObject): JSONObject {
        val guard = configGuard("hotspot.set")
        if (guard != null) return guard
        val on = p.optBoolean("on", false)
        val (ssid, pass, band) = checkParams(p)
        val o = JSONObject()
        if (helperAvailable()) {
            val r = if (on) helperExec("start", ssid, pass, band)
                    else helperExec("stop", "", "", "")
            val out = r.optString("output")
            // helper 约定: RESULT=true 表示指令达成目标状态（start 后开启 / stop 后关闭）
            o.put("method", "TetheringManager(helper)")
            o.put("ok", r.optBoolean("ok") && out.contains("RESULT=true"))
            o.put("detail", out)
            o.put("withConfig", ssid.isNotEmpty() || pass.isNotEmpty() || band.isNotEmpty())
            Logger.i(TAG, "set on=$on via helper TetheringManager: ${out.take(150)}")
            return o
        }
        // 回退：App 进程内 TetheringManager（无法持久化配置，仅开关）
        var cfg: Any? = buildSoftApConfig(
            ssid.takeIf { it.isNotEmpty() },
            pass.takeIf { it.isNotEmpty() },
            band.takeIf { it.isNotEmpty() }
        )
        val viaSoftAp = cfg != null
        if (on && cfg == null && (ssid.isNotEmpty() || pass.isNotEmpty() || band.isNotEmpty())) {
            cfg = buildWifiConfiguration(p)
        }
        val r = if (on) startTethering(ctx, cfg) else stopTethering(ctx)
        o.put("started", on)
        o.put("method", r.first)
        o.put("detail", r.second)
        o.put("withConfig", cfg != null)
        o.put("configType", if (cfg == null) "none" else if (viaSoftAp) "SoftApConfiguration" else "WifiConfiguration")
        Logger.i(TAG, "set on=$on via ${r.first}: ${r.second.take(120)}")
        return o
    }

    /** 构建 android.net.wifi.SoftApConfiguration（@hide，反射），用于 TetheringRequest 即时下发。
     *  三星 ROM 应用类路径不含 wifi APEX → 从 setSoftApConfiguration 参数类型反查 ClassLoader 加载。 */
    private fun buildSoftApConfig(ssid: String?, pass: String?, band: String?): Any? {
        if (ssid == null && pass == null && band == null) return null
        val safe = Regex("^[A-Za-z0-9._-]{1,32}$")
        if (ssid != null && !safe.matches(ssid)) throw IllegalArgumentException("SSID 仅允许字母数字._-")
        if (pass != null && !safe.matches(pass)) throw IllegalArgumentException("密码仅允许字母数字._-")
        try {
            // 1. 从 TetheringRequest.Builder.setSoftApConfiguration 参数拿到 SoftApConfiguration 类（其 classloader 可见 wifi APEX）
            val tbClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val setCfg = tbClass.declaredMethods.firstOrNull { it.name == "setSoftApConfiguration" }
                ?: throw NoSuchMethodException("setSoftApConfiguration not found")
            val cfgClass = setCfg.parameterTypes[0]
            val cfgLoader = cfgClass.classLoader
            Logger.d(TAG, "SoftApConfiguration loaded from ${cfgLoader?.javaClass?.name ?: "boot"}")
            // 2. 用同一 classloader 加载 Builder，运行时自适应选择可用 setter（三星 ROM 方法名有差异）
            val bClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder", true, cfgLoader)
            val b = bClass.getDeclaredConstructor().newInstance()
            val names = bClass.declaredMethods.map { it.name }.distinct()
            Logger.d(TAG, "Builder methods: ${names.joinToString(",")}")
            fun invokeSetter(pick: List<Pair<String, List<Class<*>>>>, vararg args: Any?): Boolean {
                for ((mn, ptypes) in pick) {
                    val m = bClass.declaredMethods.firstOrNull { it.name == mn && it.parameterTypes.size == ptypes.size }
                        ?: continue
                    try { m.invoke(b, *args); return true } catch (e: Throwable) {
                        Logger.w(TAG, "$mn failed: ${e.javaClass.name}: ${e.message}") }
                }
                return false
            }
            if (ssid != null)
                invokeSetter(listOf("setSsid" to listOf(String::class.java)), ssid)
            if (pass != null) {
                val ok = invokeSetter(listOf("setWpa2Passphrase" to listOf(String::class.java)), pass)
                if (!ok) {
                    // AOSP 新签名 setPassphrase(passphrase, @SecurityType int)
                    val secType = try { cfgClass.getField("SECURITY_TYPE_WPA2_PSK").getInt(null) } catch (_: Throwable) { 1 }
                    invokeSetter(listOf("setPassphrase" to listOf(String::class.java, Int::class.javaPrimitiveType!!)), pass, secType)
                }
            }
            if (band != null) {
                val bandVal = if (band.lowercase() == "5g") 2 else 1  // BAND_2GHZ=1 BAND_5GHZ=2
                invokeSetter(listOf("setBand" to listOf(Int::class.javaPrimitiveType!!)), bandVal)
            }
            return bClass.getMethod("build").invoke(b)
        } catch (e: Throwable) {
            Logger.w(TAG, "SoftApConfig build failed: ${e.javaClass.name}: ${e.message}")
            return null
        }
    }

    /** 诊断：枚举热点相关类与方法，用于真机排查 */
    fun probe(ctx: Context): JSONObject {
        val o = JSONObject()
        fun tryClass(name: String): JSONObject {
            val c = JSONObject()
            try {
                val cl = Class.forName(name)
                c.put("found", true)
                val ms = JSONArray()
                cl.declaredMethods.forEach { ms.put("${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})") }
                c.put("methods", ms)
                val cs = JSONArray()
                cl.declaredConstructors.forEach { cs.put("(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})") }
                c.put("constructors", cs)
            } catch (e: Throwable) {
                c.put("found", false)
                c.put("error", "${e.javaClass.name}: ${e.message}")
            }
            return c
        }
        o.put("SoftApConfiguration", tryClass("android.net.wifi.SoftApConfiguration"))
        o.put("SoftApConfiguration\$Builder", tryClass("android.net.wifi.SoftApConfiguration\$Builder"))
        o.put("TetheringRequest\$Builder", tryClass("android.net.TetheringManager\$TetheringRequest\$Builder"))
        // 立即尝试构建一次
        val t = JSONObject()
        try {
            val bClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val b = bClass.getDeclaredConstructor().newInstance()
            t.put("construct", "ok")
            try { bClass.getMethod("setSsid", String::class.java).invoke(b, "ProbeTest"); t.put("setSsid", "ok") }
            catch (e: Throwable) { t.put("setSsid", "${e.javaClass.name}: ${e.message}") }
            try { bClass.getMethod("setWpa2Passphrase", String::class.java).invoke(b, "probe12345"); t.put("setWpa2Passphrase", "ok") }
            catch (e: Throwable) { t.put("setWpa2Passphrase", "${e.javaClass.name}: ${e.message}") }
            try { val cfg = bClass.getMethod("build").invoke(b); t.put("build", "ok(${cfg.javaClass.name})") }
            catch (e: Throwable) { t.put("build", "${e.javaClass.name}: ${e.message}") }
        } catch (e: Throwable) {
            t.put("error", "${e.javaClass.name}: ${e.message}")
        }
        o.put("buildTest", t)
        return o
    }

    /**
     * 新热点配置到达时的完整编排（用户指定流程）：
     *   1. 停止热点（TetheringManager.stopTethering）
     *   2. 创建热点：持久化写入新配置（SemWifiManager.setSoftApConfiguration）
     *   3. 开启热点：TetheringManager.startTethering(TetheringRequest+SoftApConfiguration)
     */
    fun setConfig(ctx: Context, p: JSONObject): JSONObject {
        val guard = configGuard("hotspot.config")
        if (guard != null) return guard
        val (ssid, pass, band) = checkParams(p)
        if (ssid.isEmpty() && pass.isEmpty() && band.isEmpty())
            throw IllegalArgumentException("需要 ssid / pass / band 至少一项")
        if (!helperAvailable()) throw IllegalStateException("热点 helper 未安装 (/system/etc/s20apcfg.dex)")
        val o = JSONObject()
        // 1. 先停止热点（RESULT=true = 热点已处于关闭状态）
        val stopR = helperExec("stop", "", "", "")
        o.put("stopped", stopR.optBoolean("ok") && stopR.optString("output").contains("RESULT=true"))
        // 2. 创建（持久化写入新配置）
        val cfgR = helperExec("config", ssid, pass, band)
        val cfgOut = cfgR.optString("output")
        o.put("configured", cfgR.optBoolean("ok") && cfgOut.contains("readback"))
        o.put("configDetail", cfgOut.take(400))
        // 3. 再开启热点
        val startR = helperExec("start", "", "", "")
        val startOut = startR.optString("output")
        o.put("started", startR.optBoolean("ok") && startOut.contains("RESULT=true"))
        o.put("startDetail", startOut.take(400))
        o.put("ok", o.optBoolean("configured") && o.optBoolean("started"))
        Logger.i(TAG, "config cycle: stop=${o.optBoolean("stopped")} cfg=${o.optBoolean("configured")} start=${o.optBoolean("started")}")
        return o
    }

    private fun buildWifiConfiguration(p: JSONObject): WifiConfiguration? {
        val ssid = p.optString("ssid", "").takeIf { it.isNotEmpty() }
        val pass = p.optString("pass", "").takeIf { it.isNotEmpty() }
        if (ssid == null && pass == null) return null
        val wc = WifiConfiguration()
        if (ssid != null) wc.SSID = ssid
        if (pass != null) {
            wc.preSharedKey = pass
            wc.allowedKeyManagement.clear()
            wc.allowedKeyManagement.set(4) // WPA2_PSK
        }
        return wc
    }

    /** TetheringManager.startTethering（Android 11+，@SystemApi 走反射），失败回退 WifiManager */
    private fun startTethering(ctx: Context, cfg: Any?): Pair<String, String> {
        // 策略1: TetheringManager（tethering 系统服务 + 反射）
        try {
            val tm = ctx.getSystemService("tethering")
                ?: throw IllegalStateException("tethering service null")
            val tmClass = tm.javaClass
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass),
                InvocationHandler { _, method, _ ->
                    Logger.d(TAG, "tether cb: ${method.name}")
                    null
                })
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            if (cfg != null) {
                // 按 cfg 实际类型匹配 addSoftApConfiguration 重载（SoftApConfiguration / WifiConfiguration 均尝试）
                var applied = false
                for (m in builderClass.declaredMethods) {
                    if (m.name != "addSoftApConfiguration" && m.name != "setSoftApConfiguration") continue
                    try {
                        m.invoke(builder, cfg)
                        applied = true
                        Logger.i(TAG, "config applied via ${m.parameterTypes.joinToString() { it.simpleName }}")
                        break
                    } catch (e: Exception) {
                        Logger.w(TAG, "addSoftAp(${m.parameterTypes.joinToString() { it.simpleName }}) failed: ${e.javaClass.name}: ${e.message}")
                    }
                }
                if (!applied) Logger.w(TAG, "no usable addSoftApConfiguration overload for ${cfg.javaClass.name}")
            }
            val request = builderClass.getMethod("build").invoke(builder)
            val start = tmClass.getMethod("startTethering",
                request.javaClass, Executor::class.java, callbackClass)
            start.invoke(tm, request, Executor { it.run() }, callback)
            return Pair("TetheringManager", "invoked ok")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Logger.w(TAG, "TetheringManager denied: ${cause?.message}")
            return Pair("TetheringManager",
                "denied: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        } catch (e: Exception) {
            Logger.w(TAG, "TetheringManager failed: ${e.message}")
        }
        // 策略2: WifiManager 旧接口回退
        return try {
            val wifi = wm(ctx)
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$OnStartTetheringCallback")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass),
                InvocationHandler { _, _, _ -> null })
            val m = WifiManager::class.java.getMethod("startTethering",
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                callbackClass, android.os.Handler::class.java)
            m.invoke(wifi, TETHERING_WIFI, true, callback, android.os.Handler(ctx.mainLooper))
            Pair("WifiManager", "invoked ok")
        } catch (e: Exception) {
            Pair("WifiManager", "failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopTethering(ctx: Context): Pair<String, String> {
        return try {
            val tm = ctx.getSystemService("tethering")
                ?: throw IllegalStateException("tethering service null")
            tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(tm, TETHERING_WIFI)
            Pair("TetheringManager", "stop invoked")
        } catch (e: Exception) {
            Pair("TetheringManager", "stop failed: ${e.message}")
        }
    }
}
