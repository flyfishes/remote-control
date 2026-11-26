package com.s20.remote.modules

import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import com.s20.remote.modules.StatusModule
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * DNSExit DDNS 更新。
 * API: https://www.dnsexit.com/RemoteUpdate.sv?login=<u>&password=<p>&host=<h>&myip=<ip>
 * 返回 "0 Success" 等状态码。
 */
object DdnsModule {
    private const val TAG = "DDNS"
    private const val API = "https://www.dnsexit.com/RemoteUpdate.sv"

    fun register() {
        CommandRouter.register("ddns.update") { p -> update(p) }
        CommandRouter.register("ddns.get") { get() }
    }

    /** 获取移动数据 IPv6 全球地址（优先 rmnet 接口） */
    fun currentIpv6(): String? {
        try {
            val candidates = mutableListOf<String>()
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (!ni.isUp || ni.isLoopback) continue
                val isCell = ni.name.startsWith("rmnet")
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("fe80")) continue
                    val global = if (host.contains('%')) host.substringBefore('%') else host
                    if (global.startsWith("240") || global.startsWith("2") || global.startsWith("3")) {
                        if (isCell) return global
                        candidates.add(global)
                    }
                }
            }
            return candidates.firstOrNull()
        } catch (e: Exception) { return null }
    }

    /** 获取公网 IPv4（优先移动数据 rmnet，其次 wlan；排除私网/保留地址）。
     *  本地无公网 IPv4 时（如移动 CGNAT、WiFi 私网），回退经外部服务检测出口 IPv4。 */
    fun currentIpv4(): String? {
        try {
            val candidates = mutableListOf<String>()
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return detectPublicIpv4()) {
                if (!ni.isUp || ni.isLoopback) continue
                val isCell = ni.name.startsWith("rmnet")
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (isPrivate(ip)) continue
                    if (isCell) return ip
                    candidates.add(ip)
                }
            }
            return candidates.firstOrNull() ?: detectPublicIpv4()
        } catch (e: Exception) { return detectPublicIpv4() }
    }

    /** 外部服务检测公网出口 IPv4（CGNAT 场景本地接口拿不到公网地址） */
    fun detectPublicIpv4(): String? {
        for (svc in listOf("https://api-ipv4.ip.sb/ip", "https://api.ipify.org")) {
            val (body, status) = httpGet(svc)
            val ip = body.trim()
            if (status > 0 && Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) return ip
        }
        return null
    }

    private fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 10 || p[0] == 127 || p[0] == 0 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254) ||
                p[0] >= 224
    }

    /** 按 ipType 选取本次要更新的地址（可多个） */
    private fun resolveIps(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        when (Config.ddnsIpType) {
            "ipv4" -> currentIpv4()?.let { result.add("ipv4" to it) }
            "all" -> {
                currentIpv4()?.let { result.add("ipv4" to it) }
                currentIpv6()?.let { result.add("ipv6" to it) }
            }
            else -> currentIpv6()?.let { result.add("ipv6" to it) }
        }
        return result
    }

    fun update(p: JSONObject = JSONObject()): JSONObject {
        val force = p.optBoolean("force", false)
        val host = Config.ddnsHost
        if (host.isEmpty()) throw IllegalStateException("ddnsHost 未配置")
        val ips = resolveIps()
        val o = JSONObject()
        o.put("host", host)
        o.put("provider", Config.ddnsProvider)
        o.put("ipType", Config.ddnsIpType)
        if (ips.isEmpty()) {
            o.put("ok", false); o.put("msg", "未找到可用的公网 ${Config.ddnsIpType} 地址")
            return o
        }
        var allOk = true
        var updated = 0
        var skipped = 0
        val details = org.json.JSONArray()
        for ((type, ip) in ips) {
            val last = if (type == "ipv4") Config.ddnsLastIpv4 else Config.ddnsLastIpv6
            if (!force && ip == last && last.isNotEmpty()) {
                // IP 未变化：跳过实际请求（DNSExit 亦提示 IP 未变时不要重复提交）
                skipped++
                details.put(JSONObject()
                    .put("type", type).put("ip", ip).put("ok", true)
                    .put("skipped", true).put("msg", "IP 未变化，跳过更新"))
                continue
            }
            val url = "$API?login=${URLEncoder.encode(Config.ddnsUser, "UTF-8")}" +
                    "&password=${URLEncoder.encode(Config.ddnsPass, "UTF-8")}" +
                    "&host=${URLEncoder.encode(host, "UTF-8")}" +
                    "&myip=${URLEncoder.encode(ip, "UTF-8")}"
            val code = httpGet(url)
            // DNSExit: 0=成功, 4=IP未变化（无需重复更新，视为成功）。
            // 响应体可能带 HTTP 状态行前缀，按"行首状态码"匹配。
            val body = code.first.trim()
            val ok = Regex("(^|\\n)\\s*(0|4)(\\s|=)").containsMatchIn(body)
            if (!ok) allOk = false else updated++
            if (ok) {
                if (type == "ipv4") Config.ddnsLastIpv4 = ip else Config.ddnsLastIpv6 = ip
            }
            details.put(JSONObject()
                .put("type", type).put("ip", ip).put("ok", ok)
                .put("httpStatus", code.second).put("msg", code.first.trim()))
            Logger.i(TAG, "update $host [$type] -> $ip : ${code.first.trim()}")
        }
        o.put("ok", allOk)
        o.put("ipUsed", if (ips.size == 1) ips[0].second else JSONObject.NULL)
        o.put("updated", updated); o.put("skipped", skipped)
        o.put("details", details)
        o.put("msg", when {
            updated == 0 && skipped > 0 -> "IP 未变化，跳过更新"
            allOk -> "ok"
            else -> "部分更新失败"
        })
        return o
    }

    private fun get(): JSONObject {
        val o = JSONObject()
        o.put("provider", Config.ddnsProvider)
        o.put("user", Config.ddnsUser)
        o.put("host", Config.ddnsHost)
        o.put("ipType", Config.ddnsIpType)
        o.put("intervalMin", Config.ddnsIntervalMin)
        o.put("currentIpv4", currentIpv4() ?: JSONObject.NULL)
        o.put("currentIpv6", currentIpv6() ?: JSONObject.NULL)
        // 查询域名当前解析值
        val r = RootShell.exec("ping -c1 -W3 ${Config.ddnsHost.ifEmpty { "localhost" }} 2>&1 | head -1")
        o.put("resolvedRaw", r.stdout.trim())
        return o
    }

    /**
     * GET，返回 (body, httpStatus)；失败时 status=-1 且 body 为 error 信息。
     * 旧设备（Android 6~7）系统 CA 库过旧，遇到新证书链（如 Let's Encrypt ISRG Root X1）
     * 会报 "Trust anchor for certification path not found"——此时降级为不校验证书重试一次。
     */
    private fun httpGet(url: String): Pair<String, Int> {
        val first = httpGetImpl(url, null)
        if (first.second == -1) {
            val m = first.first
            if (m.contains("Trust anchor") || m.contains("CertPathValidator") ||
                m.contains("SSLHandshake") || m.contains("hostname verification")) {
                Logger.w(TAG, "TLS verify failed, retry without verification: ${first.first.take(120)}")
                return httpGetImpl(url, insecure = true)
            }
        }
        return first
    }

    private fun httpGetImpl(url: String, insecure: Boolean?): Pair<String, Int> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            if (insecure == true) {
                val https = conn as? HttpsURLConnection
                if (https != null) {
                    val tm = object : X509TrustManager {
                        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sc = SSLContext.getInstance("TLS")
                    sc.init(null, arrayOf(tm), java.security.SecureRandom())
                    https.sslSocketFactory = sc.socketFactory
                    https.hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            }
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            Pair(body, conn.responseCode)
        } catch (e: Exception) {
            Pair("error: ${e.message}", -1)
        } finally { conn?.disconnect() }
    }
}
