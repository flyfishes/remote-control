package com.s20.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import com.s20.remote.MainActivity
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import com.s20.remote.modules.DdnsModule
import com.s20.remote.net.HttpServer
import com.s20.remote.net.RelayClient
import kotlin.concurrent.thread

/**
 * 核心前台常驻服务：
 * 1. root 引导（appops/白名单/防冻结）
 * 2. 启动 HTTP 服务（IPv6 直连通道）
 * 3. 启动中转 WebSocket 客户端
 * 4. DDNS 定时更新 + 网络变化触发
 * 5. WakeLock 防休眠
 */
class MainService : Service() {
    private val TAG = "MainService"
    private var httpServer: HttpServer? = null
    private val relay = RelayClient()
    private var wakeLock: PowerManager.WakeLock? = null
    private var ddnsTimer: Thread? = null
    private var relayRestarter: android.content.BroadcastReceiver? = null

    companion object {
        /** 供本地 UI 读取的服务状态（同进程，无需跨进程查询） */
        @Volatile var running = false
        @Volatile var startedAt = 0L
        /** 当前中转客户端实例（本地 UI 读连接状态用） */
        @Volatile var relayRef: RelayClient? = null
    }

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "service created")
        running = true
        startedAt = System.currentTimeMillis()
        relayRef = relay
        registerModules()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, buildNotification("启动中..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, buildNotification("启动中..."))
            }
        } catch (e: Throwable) {
            // Android 14+ 对 BOOT_COMPLETED 启动的类型校验更严：降级重试普通前台
            Logger.w(TAG, "startForeground(type) failed: ${e.message}")
            try { startForeground(1, buildNotification("启动中...")) } catch (_: Throwable) {}
        }
        bootstrapRoot()
        startHttp()
        relay.onConnected = { com.s20.remote.net.StatusReporter.onRelayConnected() }
        relay.start()
        com.s20.remote.net.StatusReporter.start(this, relay)
        com.s20.remote.modules.SwitchGuard.start(this)
        startWakeLock()
        startDdnsTimer()
        registerNetworkCallback()
        // relay 配置热更新：ConfigModule 改 relayEnabled/relayUrl 后发此广播，立即重启 relay
        relayRestarter = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                Logger.i(TAG, "relay restart broadcast")
                relay.stop()
                relay.start()
            }
        }
        registerReceiver(relayRestarter, android.content.IntentFilter("com.s20.remote.RESTART_RELAY"))
        Logger.i(TAG, "service ready")
    }

    /** 注册所有指令模块 */
    private fun registerModules() {
        com.s20.remote.modules.StatusModule.register(this)
        com.s20.remote.modules.HotspotModule.register(this)
        com.s20.remote.modules.SmsModule.register(this)
        com.s20.remote.modules.AppModule.register(this)
        com.s20.remote.modules.FileModule.register()
        com.s20.remote.modules.DataModule.register()
        com.s20.remote.modules.SwitchModule.register(this)
        com.s20.remote.modules.DdnsModule.register()
        com.s20.remote.modules.ConfigModule.register(this)
        com.s20.remote.modules.TlsModule.register(this)
        com.s20.remote.modules.SwitchGuard.register(this)
        com.s20.remote.modules.ScreenModule.register(this)
        com.s20.remote.modules.MediaModule.register(this)
        Logger.i(TAG, "modules registered: ${com.s20.remote.core.CommandRouter.commands().size}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(relayRestarter) } catch (_: Throwable) {}
        httpServer?.stop()
        com.s20.remote.net.StatusReporter.stop()
        com.s20.remote.modules.SwitchGuard.stop()
        relay.stop()
        wakeLock?.release()
        running = false
        startedAt = 0L
        relayRef = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Logger.i(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** root 引导：确保 su 可用 + 系统级保活 */
    private fun bootstrapRoot() {
        thread(name = "bootstrap") {
            if (!RootShell.available()) {
                Logger.w(TAG, "root not available (Magisk 未授权?)")
                return@thread
            }
            val cmds = listOf(
                "dumpsys deviceidle whitelist +${packageName}",
                "appops set $packageName READ_SMS allow",
                "appops set $packageName WRITE_SMS allow",
                "appops set $packageName READ_CONTACTS allow",
                "cmd appops set $packageName SMS_FINANCIAL_TRANSACTIONS allow",
                // Samsung 防冻结：加入休眠排除（OneUI）
                "settings put global cached_apps_freezer_enabled 0",
            )
            for (c in cmds) {
                val r = RootShell.exec(c)
                Logger.d(TAG, "bootstrap [$c] -> ${if (r.ok) "ok" else "fail: ${r.stderr.take(60)}"}")
            }
            // 运行时权限（root grant 兜底）
            val perms = listOf(
                "android.permission.READ_SMS", "android.permission.SEND_SMS",
                "android.permission.RECEIVE_SMS", "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.POST_NOTIFICATIONS"
            )
            for (p in perms) RootShell.exec("pm grant $packageName $p")
            // MANAGE_EXTERNAL_STORAGE 需 appops
            RootShell.exec("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
            Logger.i(TAG, "bootstrap done")
        }
    }

    private fun startHttp() {
        thread(name = "http-start") {
            val webHtml = try {
                assets.open("index.html").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Logger.w(TAG, "load web asset failed: ${e.message}")
                "<html><body>Web console asset missing</body></html>"
            }
            try {
                val port = Config.httpPort
                var factory: javax.net.ssl.SSLServerSocketFactory? = null
                if (Config.tlsEnabled) {
                    factory = com.s20.remote.net.TlsManager.serverSocketFactory(this@MainService)
                        ?: com.s20.remote.net.TlsManager.serverSocketFactory(this@MainService, forceNew = true)
                }
                if (factory != null) {
                    httpServer = HttpServer(webHtml, port, factory)
                    httpServer!!.start()
                    Logger.i(TAG, "HTTPS listening :$port (all interfaces)")
                    updateNotification("HTTPS:$port 运行中")
                } else if (Config.tlsEnabled) {
                    // 证书初始化失败：仅监听本机明文，保证可通过 adb 修复，不对外暴露
                    Logger.e(TAG, "TLS init failed, fallback to 127.0.0.1 plain http for recovery")
                    httpServer = HttpServer(webHtml, port, null, "127.0.0.1")
                    httpServer!!.start()
                    updateNotification("TLS 失败，仅本机 127.0.0.1:$port")
                } else {
                    httpServer = HttpServer(webHtml, port, null, "::")
                    httpServer!!.start()
                    Logger.w(TAG, "plain HTTP listening :$port (tlsEnabled=false)")
                    updateNotification("HTTP:$port 运行中（未加密）")
                }
            } catch (e: Exception) {
                // 端口被占用等：重试 3 次（间隔 5s），仍失败在通知栏标红
                Logger.e(TAG, "http start failed: ${e.message}")
                var ok = false
                for (i in 1..3) {
                    Thread.sleep(5000)
                    try {
                        val port2 = Config.httpPort
                        val f2 = if (Config.tlsEnabled)
                            com.s20.remote.net.TlsManager.serverSocketFactory(this@MainService) else null
                        val hs = HttpServer(webHtml, port2, f2, if (f2 != null) "::" else "127.0.0.1")
                        hs.start()
                        httpServer = hs
                        Logger.i(TAG, "http retry #$i ok :$port2")
                        updateNotification("HTTPS:$port2 运行中")
                        ok = true
                        break
                    } catch (e2: Exception) {
                        Logger.e(TAG, "http retry #$i failed: ${e2.message}")
                    }
                }
                if (!ok) updateNotification("HTTPS 启动失败，请检查端口")
            }
        }
    }

    private fun startWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s20:remote").apply {
            setReferenceCounted(false)
            acquire(365L * 24 * 60 * 60 * 1000) // 最长1年，防休眠冻结
        }
    }

    private fun startDdnsTimer() {
        ddnsTimer = thread(name = "ddns-timer") {
            while (true) {
                if (Config.ddnsHost.isNotEmpty()) {
                    try { DdnsModule.update() } catch (e: Exception) { Logger.w(TAG, "ddns: ${e.message}") }
                }
                Thread.sleep(Config.ddnsIntervalMin * 60L * 1000)
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Logger.i(TAG, "network available, trigger ddns")
                        if (Config.ddnsHost.isNotEmpty()) {
                            thread { try { DdnsModule.update() } catch (_: Exception) {} }
                        }
                    }
                })
        } catch (e: Exception) { Logger.w(TAG, "net callback: ${e.message}") }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("s20_remote", "Remote Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            return Notification.Builder(this, "s20_remote")
                .setContentTitle("远程控制")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
        // API 23-25：无通知渠道，走旧版构造器
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("远程控制")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }
}
