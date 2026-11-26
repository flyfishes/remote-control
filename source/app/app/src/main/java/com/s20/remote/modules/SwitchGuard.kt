package com.s20.remote.modules

import android.content.Context
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 开关守卫：周期性检查 WiFi / 热点 / 数据 / 蓝牙 / 定位，
 * 对开启了「自动打开」的项，一旦检测到关闭就立即重新打开。
 *
 * 设计要点：
 *  - **独立线程，不依赖中转连接**。若把恢复逻辑挂在 StatusReporter 上，
 *    WiFi 被关 → 中转断开 → 心跳不再执行 → 永远无法恢复。
 *  - 热点与 WiFi 在 SM-G9810 上互斥（开热点会关 WiFi），因此：
 *      · 热点开着时不动 WiFi
 *      · WiFi 开着时不动热点
 *    两者同时关闭且都勾选时优先恢复 WiFi（保连通性），下一轮热点因 WiFi 已开而跳过。
 *  - 恢复成功后通过 StatusReporter 追加一条上报（中转在线时），页面操作记录可见。
 */
object SwitchGuard {
    private const val TAG = "SwitchGuard"

    @Volatile private var running = false
    private var ctxRef: Context? = null
    private var loopThread: Thread? = null

    @Volatile private var lastRunAt = 0L
    @Volatile private var lastActions: List<String> = emptyList()

    /** 尚未成功上报给中转的恢复记录（离线期间产生），重连后并入「启动上报」 */
    private val pending = ArrayDeque<String>()

    fun pendingActions(): List<String> = synchronized(pending) {
        val l = pending.toList(); pending.clear(); l
    }

    fun register(ctx: Context) {
        ctxRef = ctx.applicationContext
        CommandRouter.register("guard.get") { state() }
        CommandRouter.register("guard.check") { runOnce(ctxRef ?: ctx) }
    }

    fun start(ctx: Context) {
        ctxRef = ctx.applicationContext
        if (running) return
        running = true
        loopThread = thread(name = "switch-guard") {
            // 启动后先等一会儿，避免与开机自启的服务初始化抢 root
            try { Thread.sleep(8000) } catch (_: InterruptedException) { return@thread }
            while (running) {
                try {
                    val c = ctxRef
                    if (c != null) runOnce(c)
                } catch (e: Exception) {
                    Logger.w(TAG, "guard: ${e.message}")
                }
                try { Thread.sleep(Config.guardSec.coerceIn(30, 600) * 1000L) }
                catch (_: InterruptedException) { return@thread }
            }
        }
        Logger.i(TAG, "started (interval=${Config.guardSec}s)")
    }

    fun stop() {
        running = false
        loopThread?.interrupt()
        loopThread = null
        Logger.i(TAG, "stopped")
    }

    fun state(): JSONObject = JSONObject()
        .put("running", running)
        .put("intervalSec", Config.guardSec)
        .put("lastRunAt", lastRunAt)
        .put("lastActions", JSONArray(lastActions))
        .put("autoRestore", JSONObject()
            .put("wifi", Config.autoRestoreWifi)
            .put("hotspot", Config.autoRestoreHotspot)
            .put("data", Config.autoRestoreData)
            .put("bluetooth", Config.autoRestoreBluetooth)
            .put("location", Config.autoRestoreLocation))

    /** 执行一轮检查（也供 guard.check 指令手动触发） */
    fun runOnce(ctx: Context): JSONObject {
        // 自动恢复依赖 root 写开关（svc/settings/cmd）；无 root 时读得到状态但写不动，直接跳过避免无效轮询
        if (!com.s20.remote.core.RootShell.available()) {
            return JSONObject().put("skipped", true)
                .put("reason", "no-root: 开关自动恢复需要 root")
        }
        val before = SwitchModule.get(ctx)
        val wifiOn = before.optBoolean("wifi")
        val hotspotOn = before.optBoolean("hotspot")
        val actions = mutableListOf<String>()
        val failures = mutableListOf<String>()
        /** 本轮动态跟踪 WiFi 状态：恢复 WiFi 后必须立即反映，否则会造成热点/wifi 同时打开 */
        var wifiNow = wifiOn

        // 1) WiFi：热点开启时跳过（开 WiFi 会关掉热点，形成互斥震荡）
        if (Config.autoRestoreWifi && !wifiOn && !hotspotOn) {
            if (turnOn(ctx, "wifi", 2500) { SwitchModule.setWifi(JSONObject().put("on", true)) }) {
                actions.add("WiFi 检测到关闭，已自动打开")
                wifiNow = true
            } else failures.add("WiFi 自动打开失败")
        }

        // 2) 移动数据
        if (Config.autoRestoreData && !before.optBoolean("data")) {
            if (turnOn(ctx, "data", 2500) { DataModule.set(JSONObject().put("on", true)) }) {
                actions.add("移动数据检测到关闭，已自动打开")
            } else failures.add("移动数据自动打开失败")
        }

        // 3) 蓝牙（系统启用耗时较长，回读等待 5s）
        if (Config.autoRestoreBluetooth && !before.optBoolean("bluetooth")) {
            if (turnOn(ctx, "bluetooth", 5000) { SwitchModule.setBt(JSONObject().put("on", true)) }) {
                actions.add("蓝牙检测到关闭，已自动打开")
            } else failures.add("蓝牙自动打开失败")
        }

        // 4) 定位
        if (Config.autoRestoreLocation && !before.optBoolean("location")) {
            if (turnOn(ctx, "location", 1200) { SwitchModule.setLoc(JSONObject().put("on", true)) }) {
                actions.add("定位检测到关闭，已自动打开")
            } else failures.add("定位自动打开失败")
        }

        // 5) 热点：WiFi 开着时跳过（开热点会关 WiFi）；用 wifiNow 判断本轮刚恢复的 WiFi
        if (Config.autoRestoreHotspot && !hotspotOn && !wifiNow) {
            if (turnOn(ctx, "hotspot", 6000) { HotspotModule.setState(ctx, JSONObject().put("on", true)) }) {
                actions.add("热点检测到关闭，已自动打开")
            } else failures.add("热点自动打开失败")
        }

        lastRunAt = System.currentTimeMillis()
        lastActions = actions
        if (actions.isNotEmpty()) {
            synchronized(pending) {
                actions.forEach { if (pending.size < 20) pending.addLast(it) }
            }
            Logger.i(TAG, "restored: ${actions.joinToString("；")}")
            com.s20.remote.net.StatusReporter.notifyRestored(actions)
        }
        if (failures.isNotEmpty()) Logger.w(TAG, "failed: ${failures.joinToString("；")}")

        return JSONObject()
            .put("ok", true)
            .put("actions", JSONArray(actions))
            .put("failures", JSONArray(failures))
            .put("switches", before)
            .put("ts", lastRunAt)
    }

    /** 执行打开并回读确认；成功后返回 true */
    private fun turnOn(ctx: Context, key: String, waitMs: Long, exec: () -> JSONObject): Boolean {
        return try {
            val r = exec()
            if (r.has("ok") && !r.optBoolean("ok")) {
                Logger.w(TAG, "$key set failed: ${r.optString("error").ifEmpty { r.optString("detail") }.take(120)}")
            }
            Thread.sleep(waitMs)
            SwitchModule.get(ctx).optBoolean(key)
        } catch (e: Exception) {
            Logger.w(TAG, "$key exec: ${e.message}")
            false
        }
    }
}
