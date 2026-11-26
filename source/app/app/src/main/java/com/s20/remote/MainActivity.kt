package com.s20.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.s20.remote.core.Capabilities
import com.s20.remote.core.Config
import com.s20.remote.core.IdleScreenOff
import com.s20.remote.core.Logger
import com.s20.remote.core.PinBoot
import com.s20.remote.core.RootShell
import com.s20.remote.modules.DdnsModule
import com.s20.remote.modules.StatusModule
import com.s20.remote.net.StatusReporter
import com.s20.remote.net.TlsManager
import com.s20.remote.service.MainService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 本地控制台首页（单页）：
 *   顶部徽标（Root/版本） → 服务状态 ③ → 功能按钮 ② → 9 个折叠配置分组 ① → 日志 ③ → 版本行
 *
 * 设计要点：
 *  - 所有参数都能在本地改：每个分组内的输入项由 [bindings] 统一读写，保存走 [Config.setJson]（边界校验一致）
 *  - 按钮状态统一由 [render] 依据 MainService.running / 中转连接 / 配置项计算，2 秒刷新一次
 *  - 状态与日志全部来自同进程对象（MainService/StatusReporter/Logger），不额外发网络请求
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        /** 前台空闲多少秒后自动熄屏 */
        private const val IDLE_SCREEN_OFF_SEC = 30
    }

    private val C_BG = 0xFFF5F6FA.toInt()
    private val C_BORD = 0xFFE2E1DB.toInt()
    private val C_TX = 0xFF2C2C2A.toInt()
    private val C_TX2 = 0xFF888780.toInt()
    private val C_BLUE = 0xFF185FA5.toInt()
    private val C_GREEN = 0xFF0F6E56.toInt()
    private val C_RED = 0xFFA32D2D.toInt()
    private val C_AMBER = 0xFF854F0B.toInt()
    private val C_FILL = 0xFFF1EFE8.toInt()

    private val dp: Float get() = resources.displayMetrics.density
    private fun dpi(v: Int): Int = (v * dp).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private var rootState = 0          // 0=未检测 1=检测中 2=可用 3=不可用
    private var ddnsBusy = false
    private var logPaused = false
    private var cachedLanIp = ""

    // 状态视图
    private lateinit var tvRootBadge: TextView
    private lateinit var dot: View
    private lateinit var tvSvc: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvDirect: TextView
    private lateinit var tvRelay: TextView
    private lateinit var tvLan: TextView
    private lateinit var tvDdns: TextView
    private lateinit var tvLastPush: TextView
    private lateinit var btnSvcToggle: Button
    private lateinit var btnReboot: Button
    private lateinit var btnDdns: Button
    private lateinit var btnRoot: Button
    private var rebootDialog: AlertDialog? = null
    private var rebootLeft = 0
    private var rebootBusy = false
    /** 首页空闲自动熄屏：开关（计时统一由 core/IdleScreenOff 负责） */
    private lateinit var swIdleOff: SwitchCompat
    /** 重启倒计时用独立 Handler：首页 2 秒 ticker 的 stopTicker() 不会误杀倒计时 */
    private val dialogHandler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    /** 配置分组：key -> 输入控件绑定 */
    private sealed class Binding {
        class Text(val et: EditText) : Binding()
        class Num(val et: EditText) : Binding()
        class Bool(val sw: SwitchCompat) : Binding()
        class Choice(val sp: Spinner, val values: List<String>) : Binding()
    }

    private val bindings = LinkedHashMap<String, Binding>()
    private val groupSummaries = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        requestRuntimePerms()

        val root = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isSaveEnabled = false   // 不恢复上次滚动位置：每次打开都停在顶部
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14), dpi(10), dpi(14), dpi(16))
        }
        root.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        buildHeader(box)
        buildStatusCard(box)
        buildFunctionCard(box)
        buildCapsCard(box)
        buildConfigSection(box)
        buildLogCard(box)
        buildFooter(box)

        setContentView(root)
        root.post { root.scrollTo(0, 0) }
    }

    // ================= 顶部 =================

    private fun buildHeader(box: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpi(2), dpi(2), dpi(2), dpi(10))
        }
        row.addView(TextView(this).apply {
            text = "远程控制"; textSize = 19f; setTextColor(C_TX); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        tvRootBadge = TextView(this).apply {
            text = "Root 检测中"; textSize = 11f; setTextColor(C_TX2)
            setPadding(dpi(8), dpi(2), dpi(8), dpi(2))
            background = rounded(C_FILL, C_BORD, 12)
        }
        row.addView(tvRootBadge)
        box.addView(row)
    }

    // ================= 状态卡 =================

    private fun buildStatusCard(box: LinearLayout) {
        val card = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_TX2) }
        }
        head.addView(dot, LinearLayout.LayoutParams(dpi(9), dpi(9)).apply { rightMargin = dpi(8) })
        tvSvc = TextView(this).apply { text = "服务状态检测中"; textSize = 15f; setTextColor(C_TX) }
        head.addView(tvSvc)
        tvUptime = TextView(this).apply { textSize = 11f; setTextColor(C_TX2) }
        head.addView(tvUptime, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(8) })
        card.addView(head)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(6), 0, 0) }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvDirect = kv("直连", "—"); tvRelay = kv("中转", "—")
        tvLan = kv("内网", "—"); tvDdns = kv("DDNS", "—")
        r1.addView(tvDirect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        r1.addView(tvRelay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        r2.addView(tvLan, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        r2.addView(tvDdns, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        grid.addView(r1); grid.addView(r2)
        card.addView(grid)

        card.addView(divider())
        tvLastPush = TextView(this).apply {
            text = "最近上报：—"; textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(6), 0, 0)
        }
        card.addView(tvLastPush)
        box.addView(card)
    }

    // ================= 功能卡 =================

    private fun buildFunctionCard(box: LinearLayout) {
        val card = card()
        card.addView(sectionTitle("功能", "按钮随状态变灰 / 可用"))
        val g1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(4), 0, 0) }
        val g2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8), 0, 0) }
        btnSvcToggle = actionButton("启动服务", primary = true)
        btnReboot = actionButton("重启设备", danger = true)
        btnDdns = actionButton("更新 DDNS")
        btnRoot = actionButton("检测 Root")
        g1.addView(btnSvcToggle, rowLp(0, true))
        g1.addView(btnReboot, rowLp(dpi(8), true))
        g2.addView(btnDdns, rowLp(0, true))
        g2.addView(btnRoot, rowLp(dpi(8), true))
        card.addView(g1); card.addView(g2)

        btnSvcToggle.setOnClickListener {
            if (MainService.running) {
                stopService(Intent(this, MainService::class.java))
                Toast.makeText(this, "已请求停止服务", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, MainService::class.java))
                Toast.makeText(this, "已请求启动服务", Toast.LENGTH_SHORT).show()
            }
            handler.postDelayed({ render() }, 800)
        }
        btnReboot.setOnClickListener { askReboot() }
        btnDdns.setOnClickListener { runDdns() }
        btnRoot.setOnClickListener { checkRoot() }

        card.addView(divider())
        swIdleOff = SwitchCompat(this).apply {
            text = "本 App 前台空闲 ${IDLE_SCREEN_OFF_SEC} 秒自动熄屏"
            textSize = 13f
            setTextColor(C_TX)
            isChecked = Config.appIdleScreenOff
            setPadding(0, dpi(8), 0, 0)
        }
        swIdleOff.setOnCheckedChangeListener { _, v ->
            Config.appIdleScreenOff = v
            IdleScreenOff.onToggle(v)
            Toast.makeText(this, if (v) "已开启：前台 ${IDLE_SCREEN_OFF_SEC} 秒无操作自动熄屏" else "已关闭自动熄屏", Toast.LENGTH_SHORT).show()
        }
        card.addView(swIdleOff)

        card.addView(TextView(this).apply {
            text = "启停合一：按当前状态显示「启动服务」或「停止服务」。重启需 Root，弹出 5 秒倒计时，期间可取消。" +
                "空闲熄屏等同按电源键，用于避免重启后解锁打开本 App 时屏幕一直不熄灭；正在输入配置或弹窗倒计时时不触发。"
            textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(8), 0, 0)
        })
        box.addView(card)
    }

    // ================= 重启（5 秒倒计时确认） =================

    private fun askReboot() {
        if (rebootDialog != null) return
        rebootLeft = 5
        val d = AlertDialog.Builder(this)
            .setTitle("重启设备")
            .setMessage("设备将在 5 秒后自动重启。\n\n重启会中断当前连接，服务随开机自启恢复，期间网页与中转均不可用。")
            .setPositiveButton("立即重启") { _, _ -> confirmReboot() }
            .setNegativeButton("取消") { _, _ -> cancelReboot() }
            .setCancelable(true)
            .create()
        d.setOnDismissListener {
            dialogHandler.removeCallbacks(rebootTick)
            rebootDialog = null
            IdleScreenOff.setBlocked(false)
            render()
        }
        rebootDialog = d
        IdleScreenOff.setBlocked(true)
        d.show()
        dialogHandler.postDelayed(rebootTick, 1000)
        render()
    }

    private val rebootTick = object : Runnable {
        override fun run() {
            val d = rebootDialog
            if (d == null) return
            rebootLeft--
            if (rebootLeft <= 0) {
                rebootDialog = null
                dialogHandler.removeCallbacks(this)
                try { d.dismiss() } catch (_: Exception) {}
                doReboot()
                return
            }
            d.setMessage("设备将在 $rebootLeft 秒后自动重启。\n\n重启会中断当前连接，服务随开机自启恢复，期间网页与中转均不可用。")
            dialogHandler.postDelayed(this, 1000)
        }
    }

    private fun confirmReboot() {
        dialogHandler.removeCallbacks(rebootTick)
        val d = rebootDialog
        rebootDialog = null
        try { d?.dismiss() } catch (_: Exception) {}
        doReboot()
    }

    private fun cancelReboot() {
        dialogHandler.removeCallbacks(rebootTick)
        val d = rebootDialog
        rebootDialog = null
        try { d?.dismiss() } catch (_: Exception) {}
        Toast.makeText(this, "已取消重启", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun doReboot() {
        if (rebootBusy) return
        rebootBusy = true
        Toast.makeText(this, "正在重启…", Toast.LENGTH_LONG).show()
        Logger.i("MainActivity", "reboot requested from UI")
        thread(name = "ui-reboot") {
            val r = try {
                StatusModule.rebootDevice()
            } catch (e: Exception) {
                org.json.JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            val ok = r.optBoolean("ok", false)
            if (!ok) {
                runOnUiThread {
                    rebootBusy = false
                    Toast.makeText(this, "重启失败：" + r.optString("error", "未知原因"), Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

    // ================= 能力与权限 =================

    private fun capsSummary(): String {
        val c = Capabilities.probe()
        val perms = c.optJSONObject("perms") ?: return "能力检测中…"
        val total = perms.length()
        val granted = (0 until total).count { perms.names().getString(it).let { k -> perms.optJSONObject(k)?.optBoolean("granted") == true } }
        return (if (c.optBoolean("simulateNoRoot")) "模拟无 root · " else "") +
            "root ${if (c.optBoolean("root")) "✓" else "✗"} · " +
            "特权 ${if (c.optBoolean("privileged")) "✓" else "✗"} · " +
            "权限 $granted/$total"
    }

    private fun buildCapsCard(box: LinearLayout) {
        val card = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply { text = "能力与权限"; textSize = 14f; setTextColor(C_TX) })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        val tvSum = TextView(this).apply { text = capsSummary(); textSize = 11f; setTextColor(C_TX2) }
        head.addView(tvSum)
        val tvChev = TextView(this).apply { text = "  ›"; textSize = 15f; setTextColor(C_TX2) }
        head.addView(tvChev)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        fun rebuild() {
            body.removeAllViews()
            val c = Capabilities.probe()
            fun row(label: String, ok: Boolean, hint: String = ""): View {
                val r = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dpi(4), 0, dpi(4))
                }
                r.addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 12.5f; setTextColor(C_TX)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                })
                r.addView(TextView(this@MainActivity).apply {
                    text = if (ok) "✓ 可用" else "✗ 不可用"
                    textSize = 12f
                    setTextColor(if (ok) C_GREEN else C_RED)
                })
                if (hint.isNotEmpty()) r.addView(TextView(this@MainActivity).apply {
                    text = hint; textSize = 10f; setTextColor(C_TX2); setPadding(dpi(8), 0, 0, 0)
                })
                return r
            }
            body.addView(row("Root（su）", c.optBoolean("root"),
                if (!c.optBoolean("root")) if (c.optBoolean("simulateNoRoot")) "（模拟）" else "Magisk 授权后可用" else ""))
            body.addView(row("特权安装（priv-app）", c.optBoolean("privileged"),
                if (!c.optBoolean("privileged")) "普通安装时热点 helper 不可用" else ""))
            body.addView(row("设备管理员（无 root 熄屏）", c.optBoolean("deviceAdmin")))
            body.addView(row("所有文件访问", c.optBoolean("manageStorage")))
            body.addView(TextView(this@MainActivity).apply {
                text = "${c.optString("manufacturer")} ${c.optString("model")} · Android ${c.optString("androidVersion")} (API ${c.optInt("sdkInt")})"
                textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(6), 0, 0)
            })
            body.addView(divider())
            val perms = c.optJSONObject("perms")
            if (perms != null) {
                val names = mapOf(
                    "sms" to "发送短信", "smsRead" to "读取短信", "phone" to "电话状态",
                    "location" to "定位", "notifications" to "通知", "bluetooth" to "蓝牙")
                for ((k, label) in names) {
                    val pj = perms.optJSONObject(k) ?: continue
                    body.addView(row(label, pj.optBoolean("granted")))
                }
            }
            // 操作按钮行
            val ops = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8), 0, 0)
            }
            val btnRecheck = smallButton("重新检测")
            val btnAppInfo = smallButton("应用信息/权限")
            ops.addView(btnRecheck); ops.addView(btnAppInfo)
            if (!c.optBoolean("deviceAdmin")) {
                val btnAdmin = smallButton("激活设备管理员")
                btnAdmin.setOnClickListener {
                    try {
                        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                android.content.ComponentName(this@MainActivity, com.s20.remote.service.AdminReceiver::class.java))
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "用于无 root 时执行「熄屏」（仅锁屏权限）")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "无法打开激活页：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                ops.addView(btnAdmin)
            }
            body.addView(ops)

            btnRecheck.setOnClickListener {
                RootShell.recheck()
                val nc = Capabilities.probe(recheck = true)
                rebuild()
                tvSum.text = capsSummary()
                Toast.makeText(this@MainActivity,
                    if (nc.optBoolean("root")) "Root 可用" else "Root 不可用", Toast.LENGTH_SHORT).show()
            }
            btnAppInfo.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }

        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            if (open) rebuild()
            body.visibility = if (open) View.VISIBLE else View.GONE
            tvChev.text = if (open) "  ▾" else "  ›"
        }
        // 摘要随 2s 刷新重算（root/权限可能变化）
        groupSummaries.add { tvSum.text = capsSummary() }

        card.addView(head); card.addView(body)
        box.addView(card)
    }

    private fun runDdns() {
        if (ddnsBusy) return
        ddnsBusy = true; render()
        thread(name = "ui-ddns") {
            val msg = try {
                val r = DdnsModule.update()
                "DDNS: ${r.optString("msg")} ip=${r.optString("ipUsed")}"
            } catch (e: Exception) {
                "DDNS 失败: ${e.message}"
            }
            runOnUiThread {
                ddnsBusy = false
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun checkRoot() {
        if (rootState == 1) return
        rootState = 1; render()
        thread(name = "ui-root") {
            val ok = RootShell.available()
            runOnUiThread {
                rootState = if (ok) 2 else 3
                Toast.makeText(this, if (ok) "Root 可用" else "Root 不可用（Magisk 未授权？）", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    // ================= 配置区 =================

    private fun buildConfigSection(box: LinearLayout) {
        box.addView(TextView(this).apply {
            text = "配置（点标题展开，改完点「应用」）"; textSize = 13f; setTextColor(C_TX2)
            setPadding(dpi(2), dpi(4), dpi(2), dpi(8))
        })

        // 1) 基础与网络
        addGroup(box, "基础与网络",
            summary = { "${Config.deviceName} · :${Config.httpPort}" },
            keys = listOf("deviceName", "token", "httpPort"),
            note = "修改 Token 后需用新 token 打开网页端；端口变更需重启服务。"
        ) { body ->
            textRow(body, "deviceName", "设备名（中转/上报标识）")
            textRow(body, "token", "访问 Token", password = true)
            numRow(body, "httpPort", "HTTPS 端口", 1024, 65535)
        }

        // 2) 中转服务器
        addGroup(box, "中转服务器",
            summary = { (if (Config.relayEnabled) "已启用" else "未启用") + " · " + Config.relayUrl },
            keys = listOf("relayEnabled", "relayUrl", "relayInsecureTls"),
            note = "修改后需重启服务生效；自签证书中转可保持「跳过证书校验」为开。"
        ) { body ->
            boolRow(body, "relayEnabled", "启用公网中转（wss）")
            textRow(body, "relayUrl", "中转地址", hint = "wss://域名:端口/ws")
            boolRow(body, "relayInsecureTls", "跳过中转证书校验（自签时开启）")
        }

        // 3) DDNS
        addGroup(box, "DDNS",
            summary = { "${Config.ddnsProvider} · ${Config.ddnsHost.ifEmpty { "未配置" }} · ${Config.ddnsIntervalMin / 60}h" },
            keys = listOf("ddnsProvider", "ddnsUser", "ddnsPass", "ddnsHost", "ddnsIpType", "ddnsIntervalMin")
        ) { body ->
            choiceRow(body, "ddnsProvider", "服务商", listOf("dnsexit" to "DNSExit"))
            textRow(body, "ddnsUser", "登录账号")
            textRow(body, "ddnsPass", "登录密码", password = true, hint = "留空则不改")
            textRow(body, "ddnsHost", "域名", hint = "如 ss20.xxx.linkpc.net")
            choiceRow(body, "ddnsIpType", "IP 类型", listOf("ipv4" to "IPv4", "ipv6" to "IPv6", "all" to "全部"))
            numRow(body, "ddnsIntervalMin", "更新间隔（分钟）", 5, 1440)
        }

        // 4) 上报与告警
        addGroup(box, "上报与告警",
            summary = { "心跳 ${Config.heartbeatSec}s · 电量<${Config.batteryWarnPct}% · 存储<${Config.storageWarnGb}GB" },
            keys = listOf("heartbeatSec", "batteryWarnPct", "tempWarnC", "cpuWarnC", "storageWarnGb")
        ) { body ->
            numRow(body, "heartbeatSec", "心跳间隔（秒）", 10, 3600)
            numRow(body, "batteryWarnPct", "电量告警阈值（%）", 1, 100)
            numRow(body, "tempWarnC", "电池温度告警（℃）", 20, 90)
            numRow(body, "cpuWarnC", "CPU 温度告警（℃）", 20, 120)
            numRow(body, "storageWarnGb", "存储告警阈值（GB）", 1, 512)
        }

        // 5) HTTPS 证书
        addGroup(box, "HTTPS 证书",
            summary = { (if (Config.tlsEnabled) "HTTPS" else "HTTP 明文") + " · " + Config.tlsMode },
            keys = listOf("tlsEnabled", "tlsMode"),
            note = "关闭 HTTPS 后仅明文可用（调试用）；重新生成证书需重启服务生效。"
        ) { body ->
            boolRow(body, "tlsEnabled", "启用 HTTPS（全站 TLS）")
            choiceRow(body, "tlsMode", "证书模式", listOf("selfsigned" to "自签（自动生成）", "import" to "已导入"))
            body.addView(certInfoView())
            val b = actionButton("重新生成自签证书")
            b.setOnClickListener { regenCert() }
            body.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8) })
        }

        // 6) 唤醒与解锁
        addGroup(box, "唤醒与解锁",
            summary = { (if (Config.unlockEnabled) "自动解锁开" else "自动解锁关") + " · PIN " + (if (Config.unlockPin.isEmpty()) "未设" else "已设") },
            keys = listOf("unlockEnabled", "unlockPin"),
            note = "保存 PIN 会同步写入 magisk 开机脚本（/data/adb/modules/s20remote/service.sh）。"
        ) { body ->
            boolRow(body, "unlockEnabled", "唤醒时锁屏则自动解锁")
            textRow(body, "unlockPin", "锁屏 PIN（4-16 位数字）", password = true)
        }

        // 7) 开关自动恢复
        addGroup(box, "开关自动恢复",
            summary = { guardSummary() },
            keys = listOf("autoRestoreWifi", "autoRestoreHotspot", "autoRestoreData",
                "autoRestoreBluetooth", "autoRestoreLocation", "guardSec"),
            note = "独立线程周期检查，不依赖中转连接；开关同时关闭时优先恢复 WiFi（与热点互斥）。"
        ) { body ->
            boolRow(body, "autoRestoreWifi", "WiFi 被关 → 自动打开")
            boolRow(body, "autoRestoreData", "移动数据被关 → 自动打开")
            boolRow(body, "autoRestoreBluetooth", "蓝牙被关 → 自动打开")
            boolRow(body, "autoRestoreLocation", "定位被关 → 自动打开")
            boolRow(body, "autoRestoreHotspot", "热点被关 → 自动打开")
            numRow(body, "guardSec", "检查间隔（秒，默认300）", 30, 600)
        }

        // 8) 流量监控
        addGroup(box, "流量监控",
            summary = { (if (Config.trafficEnabled) "开" else "关") + " · ${Config.trafficQueryMode} · 阈值 ${Config.trafficWarnMb}MB · 已用 ${Config.trafficUsedMb}MB" },
            keys = listOf("trafficEnabled", "trafficQueryMode", "trafficQueryNumber", "trafficQueryCmd",
                "trafficLocalCmd", "trafficWarnMb", "trafficAlertNumber", "trafficCheckMin")
        ) { body ->
            boolRow(body, "trafficEnabled", "启用流量监控")
            choiceRow(body, "trafficQueryMode", "查询方式", listOf("sms" to "短信查询", "cmd" to "本地命令"))
            textRow(body, "trafficQueryNumber", "运营商查询号码", hint = "10086")
            textRow(body, "trafficQueryCmd", "查询短信内容", hint = "CXLL")
            textRow(body, "trafficLocalCmd", "本地查询命令（输出已用 MB）")
            numRow(body, "trafficWarnMb", "告警阈值（MB）", 1, 102400)
            textRow(body, "trafficAlertNumber", "告警短信接收号码")
            numRow(body, "trafficCheckMin", "检查间隔（分钟）", 10, 10080)
            body.addView(TextView(this).apply {
                text = "最近检查：${fmtTime(Config.trafficLastCheckAt)}"
                textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(6), 0, 0)
            })
        }

        // 9) 日志
        addGroup(box, "日志",
            summary = { (if (Config.logEnabled) "落盘开" else "落盘关") + " · " + Config.logFile },
            keys = listOf("logEnabled", "logFile")
        ) { body ->
            boolRow(body, "logEnabled", "写日志到文件")
            textRow(body, "logFile", "日志文件路径")
            val b = actionButton("清空内存日志")
            b.setOnClickListener {
                Logger.clear(); logView.text = ""
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            body.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8) })
        }
    }

    private val restartKeys = setOf("httpPort", "relayUrl", "relayEnabled", "tlsEnabled", "tlsMode", "token")

    /**
     * 生成一个可折叠配置分组卡片。
     * @param keys 该组「应用」时写回的配置键
     */
    private fun addGroup(
        parent: LinearLayout,
        title: String,
        summary: () -> String,
        keys: List<String>,
        note: String? = null,
        build: (LinearLayout) -> Unit
    ) {
        val card = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(C_TX) })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        val tvSum = TextView(this).apply { text = summary(); textSize = 11f; setTextColor(C_TX2) }
        head.addView(tvSum)
        val tvChev = TextView(this).apply { text = "  ›"; textSize = 15f; setTextColor(C_TX2) }
        head.addView(tvChev)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        build(body)

        if (note != null) body.addView(TextView(this).apply {
            text = note; textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(8), 0, 0)
        })

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dpi(10), 0, 0) }
        val tvState = TextView(this).apply { textSize = 11f; setTextColor(C_TX2) }
        val btnApply = actionButton("应用", primary = true)
        footer.addView(btnApply, LinearLayout.LayoutParams(dpi(88), LinearLayout.LayoutParams.WRAP_CONTENT))
        footer.addView(tvState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f; leftMargin = dpi(10) })
        body.addView(footer)

        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            tvChev.text = if (open) "  ▾" else "  ›"
        }

        btnApply.setOnClickListener {
            val applied = mutableListOf<String>()
            val changed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val snapshot = Config.dump()
            for (k in keys) {
                val v = readBinding(k)
                if (v == null) { failed.add(k); continue }
                if (k == "token" && v.toString().isBlank()) { failed.add(k); continue }
                val before = snapshot.opt(k)?.toString()
                if (Config.setJson(k, v)) {
                    applied.add(k)
                    if (before != currentValue(k)?.toString()) changed.add(k)
                } else failed.add(k)
            }
            // PIN 同步开机脚本
            if (applied.contains("unlockPin")) {
                thread(name = "ui-pin") { PinBoot.sync(Config.unlockPin) }
            }
            reloadBindings()
            groupSummaries.forEach { it.invoke() }
            tvSum.text = summary()
            tvState.text = when {
                failed.isNotEmpty() -> "部分失败：${failed.joinToString("、")}"
                changed.isEmpty() -> "无改动"
                changed.any { it in restartKeys } -> "已保存，重启服务生效"
                else -> "已保存"
            }
            tvState.setTextColor(if (failed.isNotEmpty()) C_RED else C_GREEN)
            Toast.makeText(this, tvState.text, Toast.LENGTH_SHORT).show()
            render()
        }

        card.addView(head)
        card.addView(body)
        parent.addView(card)
        groupSummaries.add { tvSum.text = summary() }
    }

    private fun certInfoView(): TextView = TextView(this).apply {
        textSize = 11f; setTextColor(C_TX2); setPadding(0, dpi(8), 0, 0)
        try {
            val i = TlsManager.info(this@MainActivity)
            if (!i.optBoolean("ok", false)) {
                text = "证书：${i.optString("error", "未生成")}"
            } else {
                val sans = StringBuilder()
                val arr = i.optJSONArray("san")
                if (arr != null) for (k in 0 until minOf(arr.length(), 4)) {
                    if (sans.isNotEmpty()) sans.append(" ")
                    sans.append(arr.optString(k))
                }
                text = "SHA-256 ${i.optString("sha256")}\n有效期至 ${i.optString("notAfter")}\nSAN ${sans}"
            }
        } catch (e: Exception) {
            text = "证书信息读取失败：${e.message}"
        }
    }

    private fun regenCert() {
        Toast.makeText(this, "正在重新生成…", Toast.LENGTH_SHORT).show()
        thread(name = "ui-cert") {
            val msg = try {
                TlsManager.invalidate()
                val r = TlsManager.generate(this)
                if (r.optBoolean("ok", false)) "证书已重新生成，重启服务生效" else "生成失败：${r.optString("error")}"
            } catch (e: Exception) {
                "生成失败：${e.message}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    // ================= 日志 =================

    private fun buildLogCard(box: LinearLayout) {
        val card = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "日志"; textSize = 14f; setTextColor(C_TX) })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        val btnPause = smallButton("暂停")
        val btnCopy = smallButton("复制")
        val btnClear = smallButton("清空")
        head.addView(btnPause); head.addView(btnCopy); head.addView(btnClear)
        card.addView(head)

        logScroll = ScrollView(this).apply {
            setPadding(0, dpi(8), 0, 0)
        }
        logView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF444441.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFFF7F6F2.toInt())
            setPadding(dpi(8), dpi(6), dpi(8), dpi(6))
            // 不要可获焦点：否则每次刷新文本都会让外层 ScrollView 自动滚到底部
            isFocusable = false
            isFocusableInTouchMode = false
        }
        logScroll.addView(logView)
        card.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(180)))
        btnPause.setOnClickListener {
            logPaused = !logPaused
            btnPause.text = if (logPaused) "继续" else "暂停"
        }
        btnCopy.setOnClickListener {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("s20log", logView.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            Logger.clear(); logView.text = ""; Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
        box.addView(card)
    }

    // ================= 版本 =================

    private fun buildFooter(box: LinearLayout) {
        val (vn, vc) = versionInfo()
        box.addView(TextView(this).apply {
            text = "远程控制 v$vn (build $vc)\nAndroid ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 11f; setTextColor(C_TX2); setPadding(dpi(2), dpi(2), dpi(2), dpi(8))
        })
    }

    private fun versionInfo(): Pair<String, Long> = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        (pi.versionName ?: "?") to code
    } catch (e: Exception) {
        "?" to 0L
    }

    // ================= 刷新（每 2 秒）=================

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                render()
                if (!logPaused) refreshLog()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(r)
    }

    private fun stopTicker() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun render() {
        // Root 徽标
        when (rootState) {
            1 -> { tvRootBadge.text = "Root 检测中"; styleBadge(tvRootBadge, C_FILL, C_TX2, C_BORD) }
            2 -> { tvRootBadge.text = "Root 可用"; styleBadge(tvRootBadge, 0xFFE1F5EE.toInt(), C_GREEN, 0xFF9FE1CB.toInt()) }
            3 -> { tvRootBadge.text = "Root 不可用"; styleBadge(tvRootBadge, 0xFFFCEBEB.toInt(), C_RED, 0xFFF7C1C1.toInt()) }
            else -> { tvRootBadge.text = "Root 未检测"; styleBadge(tvRootBadge, C_FILL, C_TX2, C_BORD) }
        }

        // 服务状态
        val running = MainService.running
        (dot.background as? GradientDrawable)?.setColor(if (running) C_GREEN else C_RED)
        tvSvc.text = if (running) "服务运行中" else "服务已停止"
        tvUptime.text = if (running && MainService.startedAt > 0) "已运行 ${elapsed(MainService.startedAt)}" else ""

        tvDirect.text = kvText("直连", (if (Config.tlsEnabled) "HTTPS" else "HTTP") + " :${Config.httpPort}")
        val relayRef = MainService.relayRef
        val relayText = when {
            !Config.relayEnabled -> "未启用"
            relayRef?.connected == true -> "已连接"
            running -> "未连接"
            else -> "—"
        }
        tvRelay.text = kvText("中转", relayText)
        tvRelay.setTextColor(if (relayText == "已连接") C_GREEN else C_TX)

        if (tickCount % 5 == 0) cachedLanIp = lanIp()
        tvLan.text = kvText("内网", cachedLanIp.ifEmpty { "—" })
        tvDdns.text = kvText("DDNS", Config.ddnsHost.ifEmpty { "未配置" })

        // 最近上报
        tvLastPush.text = lastPushText()

        // 按钮可用性：启停合一，按当前状态切换文案与配色
        if (running) {
            btnSvcToggle.text = "停止服务"
            styleActionButton(btnSvcToggle, danger = true, primary = false)
        } else {
            btnSvcToggle.text = "启动服务"
            styleActionButton(btnSvcToggle, danger = false, primary = true)
        }
        setEnabled(btnSvcToggle, rebootDialog == null && !rebootBusy)
        setEnabled(btnReboot, Capabilities.root() && rebootDialog == null && !rebootBusy)
        btnReboot.text = when {
            rebootBusy -> "重启中…"
            rebootDialog != null -> "重启 ${rebootLeft}s…"
            else -> "重启设备"
        }
        setEnabled(btnDdns, running && Config.ddnsHost.isNotEmpty() && !ddnsBusy)
        setEnabled(btnRoot, rootState != 1)
        btnDdns.text = if (ddnsBusy) "更新中…" else "更新 DDNS"

        // 摘要跟随配置（网页端/接口改过也能及时反映）
        groupSummaries.forEach { it.invoke() }

        tickCount++
    }

    private fun lastPushText(): String {
        if (!Config.relayEnabled) return "最近上报：中转未启用（仅心跳状态不上报）"
        val ts = StatusReporter.lastPushTs
        if (ts == 0L) return if (StatusReporter.running) "最近上报：暂无（等待中转连接）" else "最近上报：上报服务未启动"
        val t = fmtTime(ts)
        val kind = StatusReporter.lastPushKind
        val ok = if (StatusReporter.lastPushOk) "" else "，未送达"
        return "最近上报 $t（$kind$ok）· ${StatusReporter.lastPushSummary}"
    }

    private fun refreshLog() {
        try {
            val atBottom = logScroll.getChildAt(0)?.let {
                it.bottom - (logScroll.height + logScroll.scrollY) < 48
            } ?: true
            val lines = Logger.recent(80)
            logView.text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
            if (atBottom) logScroll.post {
                // 不用 fullScroll(FOCUS_DOWN)：它会 requestFocus 并冒泡到外层 ScrollView，
                // 把整个首页拽到日志区（表现为打开 App 不在顶部）
                val max = ((logScroll.getChildAt(0)?.height ?: 0) - logScroll.height).coerceAtLeast(0)
                logScroll.scrollTo(0, max)
            }
        } catch (e: Exception) {
            // 忽略刷新异常
        }
    }

    private fun lanIp(): String {
        return try {
            val arr = StatusModule.ips()
            val v4 = arr.optJSONObject(0)?.optJSONArray("v4") ?: return ""
            var fallback = ""
            for (i in 0 until v4.length()) {
                val a = v4.optJSONObject(i)?.optString("addr").orEmpty()
                if (a.isEmpty() || a.startsWith("127.")) continue
                if (a.startsWith("192.168.") || a.startsWith("10.") || a.startsWith("172.")) return a
                if (fallback.isEmpty()) fallback = a
            }
            fallback
        } catch (e: Exception) { "" }
    }

    private fun guardSummary(): String {
        val on = mutableListOf<String>()
        if (Config.autoRestoreWifi) on.add("WiFi")
        if (Config.autoRestoreData) on.add("数据")
        if (Config.autoRestoreBluetooth) on.add("蓝牙")
        if (Config.autoRestoreLocation) on.add("定位")
        if (Config.autoRestoreHotspot) on.add("热点")
        return (if (on.isEmpty()) "全部关闭" else on.joinToString("、")) + " · ${Config.guardSec}s"
    }

    // ================= 绑定读写 =================

    private fun readBinding(key: String): Any? = when (val b = bindings[key]) {
        is Binding.Text -> b.et.text.toString().trim()
        is Binding.Num -> b.et.text.toString().trim().toIntOrNull()
        is Binding.Bool -> b.sw.isChecked
        is Binding.Choice -> b.values.getOrNull(b.sp.selectedItemPosition)
        null -> null
    }

    private fun reloadBindings() {
        val d = Config.dump()
        for ((key, b) in bindings) {
            val v = when (key) {
                "ddnsPass" -> Config.ddnsPass
                "unlockPin" -> Config.unlockPin
                else -> d.opt(key)
            }
            when (b) {
                is Binding.Text -> if (b.et.text.toString() != v?.toString().orEmpty()) b.et.setText(v?.toString().orEmpty())
                is Binding.Num -> b.et.setText(v?.toString().orEmpty())
                is Binding.Bool -> b.sw.isChecked = v as? Boolean ?: false
                is Binding.Choice -> {
                    val idx = b.values.indexOf(v?.toString())
                    if (idx >= 0) b.sp.setSelection(idx)
                }
            }
        }
    }

    private fun currentValue(key: String): Any? {
        val d = Config.dump()
        return when (key) {
            "ddnsPass" -> Config.ddnsPass
            "unlockPin" -> Config.unlockPin
            else -> d.opt(key)
        }
    }

    // ================= 小部件 =================

    private fun textRow(body: LinearLayout, key: String, label: String, password: Boolean = false, hint: String = "") {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(8), 0, 0) }
        box.addView(labelView(label))
        val et = EditText(this).apply {
            setText(currentValue(key)?.toString().orEmpty())
            textSize = 13f
            setSingleLine(true)
            if (hint.isNotEmpty()) this.hint = hint
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(et)
        body.addView(box)
        bindings[key] = Binding.Text(et)
    }

    private fun numRow(body: LinearLayout, key: String, label: String, min: Int, max: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(8), 0, 0) }
        box.addView(labelView("$label（$min~$max）"))
        val et = EditText(this).apply {
            setText(currentValue(key)?.toString().orEmpty())
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(et)
        body.addView(box)
        bindings[key] = Binding.Num(et)
    }

    private fun boolRow(body: LinearLayout, key: String, label: String) {
        val sw = SwitchCompat(this).apply {
            text = label
            textSize = 13f
            setTextColor(C_TX)
            isChecked = currentValue(key) as? Boolean ?: false
            setPadding(0, dpi(6), 0, 0)
        }
        body.addView(sw)
        bindings[key] = Binding.Bool(sw)
    }

    private fun choiceRow(body: LinearLayout, key: String, label: String, options: List<Pair<String, String>>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(8), 0, 0) }
        box.addView(labelView(label))
        val sp = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
        }
        val cur = currentValue(key)?.toString()
        sp.setSelection(options.indexOfFirst { it.first == cur }.coerceAtLeast(0))
        box.addView(sp)
        body.addView(box)
        bindings[key] = Binding.Choice(sp, options.map { it.first })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), C_BORD, 12)
        setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpi(10) }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        setStroke(maxOf(1, dpi(1)), stroke)
    }

    private fun styleBadge(tv: TextView, fill: Int, textColor: Int, stroke: Int) {
        tv.setTextColor(textColor)
        tv.background = rounded(fill, stroke, 12)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(C_FILL)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(1)).apply { topMargin = dpi(8) }
    }

    private fun sectionTitle(t: String, sub: String = ""): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = t; textSize = 14f; setTextColor(C_TX) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        if (sub.isNotEmpty()) row.addView(TextView(this).apply { text = sub; textSize = 11f; setTextColor(C_TX2) })
        return row
    }

    private fun labelView(t: String): TextView = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(C_TX2)
    }

    private fun kv(k: String, v: String): TextView = TextView(this).apply {
        text = kvText(k, v); textSize = 12f; setTextColor(C_TX); setPadding(0, dpi(2), 0, 0)
    }

    private fun kvText(k: String, v: String): String = "$k  $v"

    private fun actionButton(text: String, primary: Boolean = false, danger: Boolean = false): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(if (primary || danger) 0xFFFFFFFF.toInt() else C_TX)
            background = when {
                danger -> rounded(C_RED, C_RED, 9)
                primary -> rounded(C_BLUE, C_BLUE, 9)
                else -> rounded(0xFFFFFFFF.toInt(), C_BORD, 9)
            }
            minHeight = 0
            minimumHeight = 0
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
        }

    private fun smallButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = false
        setTextColor(C_TX2)
        background = rounded(0xFFFFFFFF.toInt(), C_BORD, 7)
        minHeight = 0; minimumHeight = 0
        setPadding(dpi(8), dpi(2), dpi(8), dpi(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dpi(6) }
    }

    private fun rowLp(marginStart: Int, weight: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.weight = 1f
            this.leftMargin = marginStart
        }

    private fun styleActionButton(b: Button, primary: Boolean, danger: Boolean) {
        b.setTextColor(if (primary || danger) 0xFFFFFFFF.toInt() else C_TX)
        b.background = when {
            danger -> rounded(C_RED, C_RED, 9)
            primary -> rounded(C_BLUE, C_BLUE, 9)
            else -> rounded(0xFFFFFFFF.toInt(), C_BORD, 9)
        }
    }

    private fun setEnabled(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.38f
    }

    private fun elapsed(start: Long): String {
        val s = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun fmtTime(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

    // ================= 生命周期 =================

    private fun resetIdleTimer() {
        IdleScreenOff.touch()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        resetIdleTimer()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        IdleScreenOff.onUiResume()
        RootShell.recheck()
        Capabilities.probe(recheck = true)
        if (rootState == 0) checkRoot() else render()
        startTicker()
    }

    override fun onDestroy() {
        dialogHandler.removeCallbacksAndMessages(null)
        try { rebootDialog?.dismiss() } catch (_: Exception) {}
        rebootDialog = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        IdleScreenOff.onUiPause()
        stopTicker()
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }
}
