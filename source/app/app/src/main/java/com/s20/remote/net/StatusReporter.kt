package com.s20.remote.net

import android.content.Context
import android.net.Uri
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import com.s20.remote.modules.DdnsModule
import com.s20.remote.modules.SmsModule
import com.s20.remote.modules.StatusModule
import com.s20.remote.modules.SwitchModule
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 状态上报器：中转连接建立后主动推送状态，并按心跳周期上报。
 *  - 6 项固定参数（WiFi/热点/数据/蓝牙/定位/公网IPv4）任一变化 → 推完整状态（abnormal=true + 变化明细）
 *  - 电量低于阈值 / 电池温度超阈值 / CPU 温度超阈值 → 推完整状态异常
 *  - 流量超阈值（按检查间隔）→ 短信告警 + 推异常
 *  - 全部正常 → 只发极简心跳 {"type":"heartbeat","state":"ok"}
 *  - 由异常恢复 → 补推一条 recovered=true
 */
object StatusReporter {
    private const val TAG = "StatusReporter"

    private val SW_LABELS = listOf(
        "wifi" to "WiFi", "hotspot" to "热点", "data" to "数据",
        "bluetooth" to "蓝牙", "location" to "定位"
    )

    @Volatile var running = false
        private set
    private var ctxRef: Context? = null
    private var relayRef: RelayClient? = null
    private var loopThread: Thread? = null
    private var seq = 0
    private var lastSwitches: Map<String, Boolean>? = null
    private var lastPublicIp: String? = null
    private var lastPublicIpAt = 0L
    private var lastAbnormal = false
    /** 上次是否处于"阈值型"异常（电量/温度/流量）；变化型异常为一次性事件，不补推恢复 */
    private var lastThresholdAbnormal = false

    // ===== 供本地 UI 展示的最近上报（不参与上报逻辑）=====
    @Volatile var lastPushTs = 0L
        private set
    @Volatile var lastPushKind = ""
        private set
    @Volatile var lastPushSummary = ""
        private set
    @Volatile var lastPushOk = false
        private set

    fun start(ctx: Context, relay: RelayClient) {
        if (running) { ctxRef = ctx.applicationContext; relayRef = relay; return }
        ctxRef = ctx.applicationContext
        relayRef = relay
        running = true
        loopThread = thread(name = "status-reporter") { loop() }
        Logger.i(TAG, "started (heartbeat=${Config.heartbeatSec}s)")
    }

    fun stop() {
        running = false
        loopThread?.interrupt()
        loopThread = null
        Logger.i(TAG, "stopped")
    }

    /** 中转连接建立时立即推一次完整状态 */
    fun onRelayConnected() {
        thread(name = "status-push-init") {
            try {
                val ctx = ctxRef ?: return@thread
                val relay = relayRef ?: return@thread
                Thread.sleep(1500)   // 等 welcome 帧处理完
                val pending = com.s20.remote.modules.SwitchGuard.pendingActions()
                val reasons = listOf("启动上报") + pending
                pushFull(ctx, relay, reasons, abnormal = false)
                lastSwitches = snapshotSwitches(ctx)
                lastAbnormal = false
                lastThresholdAbnormal = false
            } catch (e: Exception) {
                Logger.w(TAG, "init push: ${e.message}")
            }
        }
    }

    /**
     * 开关守卫自动恢复成功后调用：中转在线时补推一条状态，
     * 让控制端能看到「某开关被关闭并已自动恢复」。
     * 中转离线时只写日志，等下次心跳/上报自然同步。
     */
    fun notifyRestored(reasons: List<String>) {
        if (reasons.isEmpty()) return
        thread(name = "status-push-guard") {
            try {
                val ctx = ctxRef ?: return@thread
                val relay = relayRef ?: return@thread
                if (!relay.connected) {
                    Logger.d(TAG, "guard restore (offline): ${reasons.joinToString("；")}")
                    return@thread
                }
                pushFull(ctx, relay, reasons, abnormal = false)
                com.s20.remote.modules.SwitchGuard.pendingActions()   // 已推送，清空待补报队列
                lastSwitches = snapshotSwitches(ctx)   // 同步基线，避免下一轮误报"关→开"
                Logger.i(TAG, "guard restore pushed: ${reasons.joinToString("；")}")
            } catch (e: Exception) {
                Logger.w(TAG, "notifyRestored: ${e.message}")
            }
        }
    }

    private fun loop() {
        try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
        while (running) {
            try {
                val ctx = ctxRef
                val relay = relayRef
                if (ctx != null && relay != null && relay.connected) tick(ctx, relay)
            } catch (e: Exception) {
                Logger.w(TAG, "tick: ${e.message}")
            }
            try { Thread.sleep(Config.heartbeatSec.coerceIn(10, 3600) * 1000L) }
            catch (_: InterruptedException) { return }
        }
    }

    private fun tick(ctx: Context, relay: RelayClient) {
        val sw = snapshotSwitches(ctx)
        val changeReasons = mutableListOf<String>()
        val thresholdReasons = mutableListOf<String>()

        lastSwitches?.let { prev ->
            for ((k, label) in SW_LABELS) {
                if (prev[k] != sw[k]) {
                    changeReasons.add("$label ${if (prev[k] == true) "开→关" else "关→开"}")
                }
            }
        }
        // 公网 IPv4 变化
        val ip = publicIpCached()
        if (lastPublicIp != null && ip != null && lastPublicIp != ip) {
            changeReasons.add("公网IPv4 $lastPublicIp → $ip")
        }
        lastPublicIp = ip

        val bat = StatusModule.battery(ctx)
        val level = bat.optInt("level", -1)
        val bTemp = bat.optDouble("temperature", -1.0)
        val cpu = StatusModule.cpuTempC()
        if (level in 0 until Config.batteryWarnPct) thresholdReasons.add("电量 $level% < ${Config.batteryWarnPct}%")
        if (bTemp > Config.tempWarnC) thresholdReasons.add("电池温度 $bTemp℃ > ${Config.tempWarnC}℃")
        if (cpu > Config.cpuWarnC) thresholdReasons.add("CPU温度 ${"%.1f".format(cpu)}℃ > ${Config.cpuWarnC}℃")
        // 可用存储低于阈值
        val storage = StatusModule.storage()
        val freeGb = storage.optDouble("freeGb", -1.0)
        if (freeGb >= 0 && freeGb < Config.storageWarnGb) {
            thresholdReasons.add("可用存储 ${"%.1f".format(freeGb)}GB < ${Config.storageWarnGb}GB")
        }

        // 流量检查（按间隔，可能追加异常原因）
        maybeCheckTraffic(ctx)?.let { thresholdReasons.add(it) }

        val reasons = changeReasons + thresholdReasons
        val abnormal = reasons.isNotEmpty()
        when {
            abnormal -> pushFull(ctx, relay, reasons, abnormal = true)
            lastThresholdAbnormal -> pushFull(ctx, relay, emptyList(), abnormal = false, recovered = true)
            else -> heartbeat(relay)
        }
        lastSwitches = sw
        lastAbnormal = abnormal
        lastThresholdAbnormal = thresholdReasons.isNotEmpty()
    }

    private fun snapshotSwitches(ctx: Context): Map<String, Boolean> {
        val o = SwitchModule.get(ctx)
        return linkedMapOf(
            "wifi" to o.optBoolean("wifi"),
            "hotspot" to o.optBoolean("hotspot"),
            "data" to o.optBoolean("data"),
            "bluetooth" to o.optBoolean("bluetooth"),
            "location" to o.optBoolean("location")
        )
    }

    /** 公网 IPv4：10 分钟缓存（外部探测服务不宜频繁调用） */
    private fun publicIpCached(): String? {
        val now = System.currentTimeMillis()
        if (lastPublicIp == null || now - lastPublicIpAt > 10 * 60_000L) {
            lastPublicIp = DdnsModule.detectPublicIpv4()
            lastPublicIpAt = now
        }
        return lastPublicIp
    }

    private fun heartbeat(relay: RelayClient) {
        val msg = JSONObject()
            .put("type", "heartbeat")
            .put("device", Config.deviceName)
            .put("seq", ++seq)
            .put("ts", System.currentTimeMillis())
            .put("state", "ok")
        if (relay.sendRaw(msg.toString())) {
            Logger.d(TAG, "heartbeat #$seq")
            lastPushTs = System.currentTimeMillis()
            lastPushKind = "心跳"
            lastPushOk = true
            if (lastPushSummary.isEmpty()) lastPushSummary = "状态正常"
        }
    }

    private fun pushFull(
        ctx: Context,
        relay: RelayClient,
        reasons: List<String>,
        abnormal: Boolean,
        recovered: Boolean = false
    ) {
        val sw = SwitchModule.get(ctx)
        val bat = StatusModule.battery(ctx)
        val dev = StatusModule.deviceInfo()
        val net = StatusModule.network(ctx)
        val data = JSONObject()
            .put("switches", JSONObject()
                .put("wifi", sw.optBoolean("wifi"))
                .put("hotspot", sw.optBoolean("hotspot"))
                .put("data", sw.optBoolean("data"))
                .put("bluetooth", sw.optBoolean("bluetooth"))
                .put("location", sw.optBoolean("location")))
            .put("publicIpv4", publicIpCached() ?: JSONObject.NULL)
            .put("battery", JSONObject()
                .put("level", bat.optInt("level", -1))
                .put("charging", bat.optBoolean("charging"))
                .put("temperature", bat.optDouble("temperature", -1.0)))
            .put("cpuTemp", StatusModule.cpuTempC())
            .put("storage", StatusModule.storage())
            .put("trafficUsedMb", Config.trafficUsedMb)
            .put("device", JSONObject()
                .put("model", dev.optString("model"))
                .put("androidVersion", dev.optString("androidVersion"))
                .put("uptime", dev.optString("uptime")))
            .put("network", JSONObject()
                .put("wifi", net.optBoolean("wifi"))
                .put("cellular", net.optBoolean("cellular"))
                .put("connected", net.optBoolean("connected"))
                .put("airplane", net.optBoolean("airplane")))
            .put("abnormal", abnormal)
            .put("reasons", JSONArray(reasons))
        if (recovered) data.put("recovered", true)
        val msg = JSONObject()
            .put("type", "status")
            .put("device", Config.deviceName)
            .put("seq", ++seq)
            .put("ts", System.currentTimeMillis())
            .put("data", data)
        val ok = relay.sendRaw(msg.toString())
        // 记录最近一次上报，供本地 App 首页展示
        lastPushTs = System.currentTimeMillis()
        lastPushKind = when {
            recovered -> "恢复上报"
            abnormal -> "异常上报"
            else -> "状态上报"
        }
        lastPushOk = ok
        val level = data.optJSONObject("battery")?.optInt("level", -1) ?: -1
        val cpu = data.optDouble("cpuTemp", -1.0)
        val freeGb = data.optJSONObject("storage")?.optDouble("freeGb", 0.0) ?: 0.0
        lastPushSummary = buildString {
            append("电量 ").append(level).append("%")
            if (cpu > 0) append(" · CPU ").append("%.0f".format(cpu)).append("℃")
            append(" · 存储 ").append(freeGb).append("GB")
            if (reasons.isNotEmpty()) append(" · ").append(reasons.joinToString("、"))
        }
        Logger.i(TAG, "status push abnormal=$abnormal reasons=$reasons ok=$ok")
    }

    /** 手动立即执行一次流量检查（traffic.check），超阈时立即推送异常 */
    fun forceCheck(ctx: Context): JSONObject {
        Config.trafficLastCheckAt = 0L
        val reason = maybeCheckTraffic(ctx)
        val o = JSONObject()
            .put("usedMb", Config.trafficUsedMb)
            .put("warnMb", Config.trafficWarnMb)
            .put("alerted", reason != null)
        if (reason != null) {
            o.put("reason", reason)
            val relay = relayRef
            if (relay != null && relay.connected) pushFull(ctx, relay, listOf(reason), abnormal = true)
        }
        return o
    }

    // ===== 流量监控 =====
    /** 到检查间隔才执行；超阈报警（每天一次）并返回异常原因 */
    private fun maybeCheckTraffic(ctx: Context): String? {
        if (!Config.trafficEnabled) return null
        val now = System.currentTimeMillis()
        if (now - Config.trafficLastCheckAt < Config.trafficCheckMin * 60_000L) return null
        Config.trafficLastCheckAt = now
        val used = try {
            if (Config.trafficQueryMode == "cmd") queryTrafficByCmd() else queryTrafficBySms(ctx)
        } catch (e: Exception) {
            Logger.w(TAG, "traffic query: ${e.message}"); null
        }
        if (used == null) { Logger.w(TAG, "traffic query failed"); return null }
        Config.trafficUsedMb = used
        val threshold = Config.trafficWarnMb
        return if (used >= threshold) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            if (day != Config.trafficAlertDay && Config.trafficAlertNumber.isNotEmpty()) {
                val text = "【流量提醒】已用 ${used}MB，超过阈值 ${threshold}MB。" +
                        "时间 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
                if (SmsModule.sendText(Config.trafficAlertNumber, text)) {
                    Config.trafficAlertDay = day
                    Logger.i(TAG, "traffic alert sent to ${Config.trafficAlertNumber}: ${used}MB")
                }
            }
            "流量已用 ${used}MB ≥ ${threshold}MB"
        } else {
            if (Config.trafficAlertDay.isNotEmpty()) Config.trafficAlertDay = ""  // 回落后重置
            null
        }
    }

    /** 本地命令模式：执行命令，取输出中首个数字（单位按 MB） */
    private fun queryTrafficByCmd(): Int? {
        val cmd = Config.trafficLocalCmd.trim()
        if (cmd.isEmpty()) return null
        val r = RootShell.exec(cmd, 30_000)
        val num = Regex("\\d+(?:\\.\\d+)?").find(r.stdout)?.value?.toDoubleOrNull() ?: return null
        return num.toInt()
    }

    /** 短信模式：向运营商发查询指令，轮询回复短信并解析已用流量（MB） */
    private fun queryTrafficBySms(ctx: Context): Int? {
        val number = Config.trafficQueryNumber.trim()
        val cmdText = Config.trafficQueryCmd.trim()
        if (number.isEmpty() || cmdText.isEmpty()) return null
        val t0 = System.currentTimeMillis()
        if (!SmsModule.sendText(number, cmdText)) {
            Logger.w(TAG, "traffic query sms send failed")
            return null
        }
        Logger.i(TAG, "traffic query sms sent to $number: $cmdText")
        var waited = 0
        while (waited < 180_000) {
            Thread.sleep(10_000); waited += 10_000
            val body = latestInboundFrom(ctx, number, t0 - 5_000) ?: continue
            parseTrafficMb(body)?.let {
                Logger.i(TAG, "traffic parsed ${it}MB <- ${body.take(80)}")
                return it
            }
        }
        return null
    }

    private fun latestInboundFrom(ctx: Context, address: String, sinceTs: Long): String? {
        val uri = Uri.parse("content://sms")
        val sel = "address = ? AND type = 1 AND date >= ?"
        val args = arrayOf(address, sinceTs.toString())
        ctx.contentResolver.query(uri, arrayOf("body", "date"), sel, args, "date DESC LIMIT 1")?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return null
    }

    /** 解析运营商流量短信：优先 总量-剩余，其次 已用 */
    fun parseTrafficMb(body: String): Int? {
        fun toMb(v: Double, unit: String): Double =
            if (unit.uppercase().startsWith("G")) v * 1024 else v
        val totalRe = Regex("共\\s*([\\d.]+)\\s*(GB|MB|G|M)", RegexOption.IGNORE_CASE)
        val remainRe = Regex("剩余\\s*([\\d.]+)\\s*(GB|MB|G|M)", RegexOption.IGNORE_CASE)
        val usedRe = Regex("已用(?:量)?\\s*[:：]?\\s*([\\d.]+)\\s*(GB|MB|G|M)", RegexOption.IGNORE_CASE)
        val t = totalRe.find(body)
        val r = remainRe.find(body)
        if (t != null && r != null) {
            val total = toMb(t.groupValues[1].toDoubleOrNull() ?: return null, t.groupValues[2])
            val remain = toMb(r.groupValues[1].toDoubleOrNull() ?: return null, r.groupValues[2])
            val used = total - remain
            if (used >= 0) return used.toInt()
        }
        usedRe.find(body)?.let {
            val v = it.groupValues[1].toDoubleOrNull() ?: return null
            return toMb(v, it.groupValues[2]).toInt()
        }
        return null
    }
}
