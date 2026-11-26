package com.s20.remote.net

import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 中转通道客户端（通道B）：WebSocket 长连到 VPS 中转服务器。
 * 连接: <relayUrl>/ws?device=<name>&token=<token>
 * 收到: {"id":"x","payload":{"cmd":"...","params":{...}}}
 * 回复: {"id":"x","device":"name","response":{...指令执行结果...}}
 */
class RelayClient {
    private val TAG = "RelayClient"
    private var ws: WebSocket? = null
    private var shouldRun = false
    private var backoff = 5_000L
    /** 首次使用时构建；Config.relayInsecureTls 默认 true（自签中转也能连） */
    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // WS 长连接
            .pingInterval(25, TimeUnit.SECONDS)
        if (Config.relayInsecureTls) {
            try {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                b.sslSocketFactory(sc.socketFactory, tm)
                b.hostnameVerifier { _, _ -> true }
                Logger.w(TAG, "TLS verification DISABLED (relayInsecureTls=true)")
            } catch (e: Exception) {
                Logger.e(TAG, "insecure TLS setup failed: ${e.message}")
            }
        } else {
            Logger.i(TAG, "TLS verification enabled for relay")
        }
        return b.build()
    }

    var connected = false
        private set

    /** 连接建立回调（在 OkHttp 线程触发，耗时逻辑请自行起线程） */
    @Volatile var onConnected: (() -> Unit)? = null

    /** 直接发送一条 JSON 文本（状态上报/心跳用），返回是否已投递 */
    fun sendRaw(json: String): Boolean {
        val w = ws ?: return false
        if (!connected) return false
        return try { w.send(json) } catch (e: Exception) { Logger.w(TAG, "sendRaw: ${e.message}"); false }
    }

    fun start() {
        if (!Config.relayEnabled) { Logger.d(TAG, "relay disabled"); return }
        if (shouldRun) return
        shouldRun = true
        thread(name = "relay-loop") { loop() }
    }

    fun stop() {
        shouldRun = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    private fun loop() {
        while (shouldRun) {
            try {
                connect()
                // connect() 挂起期间由 listener 管理，退避等待重连
                var waited = 0L
                while (shouldRun && ws != null && waited < 10_000L * 1000) {
                    Thread.sleep(1000); waited += 1000
                }
            } catch (e: Exception) {
                Logger.w(TAG, "relay error: ${e.message}")
            }
            if (!shouldRun) break
            Logger.i(TAG, "reconnect in ${backoff / 1000}s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun connect() {
        val base = Config.relayUrl.trimEnd('/')
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base$sep" +
                "device=${java.net.URLEncoder.encode(Config.deviceName, "UTF-8")}" +
                "&token=${java.net.URLEncoder.encode(Config.token, "UTF-8")}"
        Logger.i(TAG, "connecting ${url.replace(Regex("token=[^&]+"), "token=***")}")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Logger.i(TAG, "connected: ${response.code}")
            connected = true
            backoff = 5_000L
            ws = webSocket
            webSocket.send(JSONObject().put("type", "hello")
                .put("device", Config.deviceName).toString())
            try { onConnected?.invoke() } catch (e: Exception) { Logger.w(TAG, "onConnected: ${e.message}") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                val id = msg.optString("id")
                val controller = msg.optString("controller")  // 回执必须带回，供中转路由给请求方
                val payload = msg.optJSONObject("payload") ?: return
                val cmd = payload.optString("cmd")
                val params = payload.optJSONObject("params") ?: JSONObject()
                Logger.d(TAG, "cmd via relay: $cmd (controller=$controller)")
                CommandRouter.dispatch(cmd, params, id) { result ->
                    val resp = JSONObject()
                        .put("id", id)
                        .put("controller", controller)
                        .put("device", Config.deviceName)
                        .put("response", result)
                    webSocket.send(resp.toString())
                }
            } catch (e: Exception) {
                Logger.e(TAG, "onMessage: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Logger.w(TAG, "failed: ${t.message}")
            connected = false
            ws = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Logger.i(TAG, "closed: $code")
            connected = false
            ws = null
        }
    }
}
