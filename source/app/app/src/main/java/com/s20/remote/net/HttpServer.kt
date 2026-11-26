package com.s20.remote.net

import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import com.s20.remote.core.CommandRouter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.net.ssl.SSLServerSocketFactory

/**
 * 嵌入式 HTTPS 服务（通道A：DDNS 直连）。
 *
 * 安全：
 *  - 默认全站 TLS（TLSv1.2/1.3）；仅在证书初始化失败时降级为 127.0.0.1 明文，避免远程失联
 *  - 所有路由（含 `/`、`/ping`、`/file`、`/log`）强制 token：X-Token 头 / ?token= / Cookie s20tk
 *  - 页面校验通过后种 cookie，后续刷新免带参数；校验失败只返回 401 未授权页，不吐控制台
 *  - `/cert` 免鉴权（仅公钥证书，供导入信任）
 *
 * 路由：
 *  GET  /        控制台（需 token）
 *  GET  /cert    自签/导入证书 PEM
 *  GET  /ping    存活探测（需 token）
 *  POST /cmd     JSON 指令（需 token）
 *  GET  /file    下载（需 token）
 *  POST /file    上传（需 token）
 *  GET  /log     运行日志（需 token）
 */
class HttpServer(
    private val webHtml: String,
    port: Int,
    sslFactory: SSLServerSocketFactory? = null,
    hostname: String = "::"
) : NanoHTTPD(hostname, port) {
    companion object {
        /** /cmd 请求体上限（截图等回传是出方向，不受此限制） */
        private const val MAX_BODY = 16 * 1024 * 1024
    }

    private val TAG = "HttpServer"
    private val secure = sslFactory != null

    init {
        if (sslFactory != null) {
            try {
                makeSecure(sslFactory, arrayOf("TLSv1.2", "TLSv1.3"))
                Logger.i(TAG, "HTTPS enabled on [$hostname]:$port")
            } catch (e: Exception) {
                Logger.e(TAG, "makeSecure failed: ${e.message}")
            }
        } else {
            Logger.w(TAG, "plain HTTP on [$hostname]:$port (no TLS)")
        }
    }

    private val unauthorizedPage = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>401 未授权</title>
<style>body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f5f6fa;color:#2c2c2a;
display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.box{background:#fff;border:1px solid #e2e1db;border-radius:12px;padding:28px 32px;max-width:420px}
h1{font-size:18px;margin:0 0 10px;color:#a32d2d}p{font-size:13px;line-height:1.8;margin:6px 0;color:#555}
code{background:#f1efe8;border-radius:4px;padding:2px 6px;font-size:12px}</style></head>
<body><div class="box"><h1>401 未授权</h1>
<p>访问令牌缺失或错误，控制台不会在此显示。</p>
<p>请在地址后附加令牌：<br><code>https://域名:端口/?token=你的访问令牌</code></p>
<p>首次通过后会写入 cookie，后续刷新无需再带参数。</p></div></body></html>"""

    override fun serve(session: IHTTPSession): Response {
        val resp = try {
            route(session)
        } catch (e: Exception) {
            Logger.e(TAG, "route ${session.uri}: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("code", 500).put("msg", e.message ?: "internal error").toString())
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "X-Token,Content-Type")
        resp.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        resp.addHeader("X-Content-Type-Options", "nosniff")
        return resp
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        if (session.method == Method.OPTIONS)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "")

        // 证书下载（公钥信息，免鉴权；便于导入信任）
        if (uri == "/cert") {
            val pem = TlsManager.certPem(com.s20.remote.App.ctx) ?: return notFound("no certificate")
            return newFixedLengthResponse(Response.Status.OK, "application/x-pem-file", pem)
        }

        val token = tokenOf(session)
        val authed = token.isNotEmpty() && token == Config.token

        // 控制台页面：必须先校验，失败只给未授权页
        if (uri == "/" || uri == "/index.html") {
            if (!authed) {
                Logger.w(TAG, "index denied from ${session.remoteIpAddress} (no/bad token)")
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html", unauthorizedPage)
            }
            val r = newFixedLengthResponse(Response.Status.OK, "text/html", webHtml)
            r.addHeader("Set-Cookie", cookieHeader(token))
            r.addHeader("Cache-Control", "no-store")
            r.addHeader("Referrer-Policy", "no-referrer")
            return r
        }

        if (!authed) {
            Logger.w(TAG, "denied ${session.uri} from ${session.remoteIpAddress} (no/bad token)")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                JSONObject().put("code", 401).put("msg", "unauthorized: bad or missing token").toString())
        }

        return when {
            uri == "/ping" -> newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("code", 0).put("msg", "pong")
                    .put("tls", secure).put("time", System.currentTimeMillis()).toString())

            uri == "/cmd" && session.method == Method.POST -> handleCmd(readBody(session))

            uri == "/file" && session.method == Method.GET -> {
                val path = session.parms["path"] ?: return badRequest("missing path")
                serveDownload(path)
            }

            uri == "/file" && session.method == Method.POST -> {
                val path = session.parms["path"] ?: return badRequest("missing path")
                handleUpload(session, path)
            }

            uri == "/log" && session.method == Method.GET -> {
                val lines = com.s20.remote.core.Logger.recent(session.parms["n"]?.toIntOrNull() ?: 200)
                newFixedLengthResponse(Response.Status.OK, "text/plain", lines.joinToString("\n"))
            }

            else -> badRequest("unknown uri: $uri")
        }
    }

    /** token 取值优先级：X-Token 头 -> ?token= -> Cookie */
    private fun tokenOf(session: IHTTPSession): String {
        val h = session.headers["x-token"] ?: session.headers["X-Token"]
        if (!h.isNullOrBlank()) return h.trim()
        val q = session.parms["token"]
        if (!q.isNullOrBlank()) return q.trim()
        val c = session.headers["cookie"] ?: return ""
        for (part in c.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && (kv[0] == "s20tk" || kv[0] == "relaytk")) return kv[1].trim()
        }
        return ""
    }

    private fun cookieHeader(token: String): String =
        "s20tk=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000" + if (secure) "; Secure" else ""

    /**
     * 读取 POST body。
     * 不能用 NanoHTTPD 的 parseBody：它在 Content-Type 无 charset 时按 US-ASCII 解码，
     * 中文会变成 ?（短信内容/关键字乱码根因）。这里按 content-length 读原始字节，固定 UTF-8 解码。
     */
    private fun readBody(session: IHTTPSession): String {
        val cl = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (cl <= 0) return ""
        // 防御：/cmd 请求体远小于此，超限直接拒绝，避免按 content-length 分配大数组导致 OOM
        if (cl > MAX_BODY) {
            Logger.w(TAG, "cmd body too large: $cl bytes, reject")
            return ""
        }
        val buf = ByteArray(cl)
        var read = 0
        try {
            val ins = session.inputStream
            while (read < cl) {
                val n = ins.read(buf, read, cl - read)
                if (n <= 0) break
                read += n
            }
        } catch (e: Exception) {
            Logger.w(TAG, "readBody: ${e.message}")
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun handleCmd(body: String): Response {
        val req = try { JSONObject(body) } catch (e: Exception) { return badRequest("bad json") }
        val cmd = req.optString("cmd")
        val params = req.optJSONObject("params") ?: JSONObject()
        val id = req.optString("id", "")
        val result = Suspendable.runBlocking { cb ->
            CommandRouter.dispatch(cmd, params, id, cb)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    private fun serveDownload(path: String): Response {
        val f = File(path)
        return if (f.exists() && f.canRead()) {
            newChunkedResponse(Response.Status.OK, "application/octet-stream", FileInputStream(f))
        } else {
            // root 回退：cat 输出流
            val pis = PipedInputStream(65536)
            val pos = PipedOutputStream(pis)
            Thread {
                try {
                    val pr = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path'"))
                    pr.inputStream.copyTo(pos)
                    pr.waitFor(); pos.close()
                } catch (e: Exception) { try { pos.close() } catch (_: Exception) {} }
            }.start()
            newChunkedResponse(Response.Status.OK, "application/octet-stream", pis)
        }
    }

    private fun handleUpload(session: IHTTPSession, path: String): Response {
        val ct = session.headers["content-type"] ?: ""
        val tmp = File.createTempFile("upl", ".tmp")
        if (ct.startsWith("multipart")) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val src = files.values.firstOrNull() ?: return badRequest("no file part")
            File(src).copyTo(tmp, overwrite = true)
        } else {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val src = map["postData"]
            if (src != null) File(src).copyTo(tmp, overwrite = true)
            else {
                val cl = session.headers["content-length"]?.toIntOrNull() ?: 0
                tmp.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    val ins = session.inputStream
                    var read = 0
                    while (read < cl) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                    }
                }
            }
        }
        val f = File(path)
        val ok = if (f.parentFile?.canWrite() == true) {
            tmp.renameTo(f) || tmp.copyTo(f, overwrite = true).let { tmp.delete(); true }
        } else {
            val r = RootShell.exec("mv '${tmp.absolutePath}' '$path'", 30_000)
            r.ok
        }
        return if (ok) newFixedLengthResponse(Response.Status.OK, "application/json",
            JSONObject().put("code", 0).put("path", path).put("size", f.length()).toString())
        else badRequest("move failed")
    }

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
            JSONObject().put("code", 400).put("msg", msg).toString())

    private fun notFound(msg: String) =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
            JSONObject().put("code", 404).put("msg", msg).toString())
}

/** NanoHTTPD 处理线程中同步等待异步指令结果 */
object Suspendable {
    fun runBlocking(block: ((JSONObject) -> Unit) -> Unit): JSONObject {
        val lock = Object()
        var result: JSONObject? = null
        block { r -> synchronized(lock) { result = r; lock.notifyAll() } }
        synchronized(lock) {
            var waited = 0L
            while (result == null && waited < 60_000) {
                try { lock.wait(1000); waited += 1000 } catch (_: InterruptedException) { break }
            }
        }
        return result ?: JSONObject().put("code", 504).put("msg", "command timeout").put("data", JSONObject.NULL)
    }
}
