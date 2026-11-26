package com.s20.remote.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** 指令路由：cmd 字符串 -> handler(params) -> JSONObject */
object CommandRouter {
    interface Handler {
        fun handle(params: JSONObject): JSONObject
        // 耗时指令异步执行时返回异步结果包装
        fun async(params: JSONObject): Boolean = false
    }

    private val handlers = ConcurrentHashMap<String, Handler>()

    fun register(cmd: String, handler: Handler) { handlers[cmd] = handler }

    fun register(cmd: String, fn: (JSONObject) -> JSONObject) {
        register(cmd, object : Handler {
            override fun handle(params: JSONObject) = fn(params)
        })
    }

    fun registerAsync(cmd: String, fn: (JSONObject) -> JSONObject) {
        register(cmd, object : Handler {
            override fun handle(params: JSONObject) = fn(params)
            override fun async(params: JSONObject): Boolean = true
        })
    }

    /** 统一入口。异步指令在独立线程执行。 */
    fun dispatch(cmd: String, params: JSONObject, id: String, onResult: (JSONObject) -> Unit) {
        val h = handlers[cmd]
        if (h == null) {
            onResult(resp(id, 404, "unknown cmd: $cmd", JSONObject.NULL))
            return
        }
        val run = {
            val result = try {
                resp(id, 0, "ok", h.handle(params) ?: JSONObject.NULL)
            } catch (e: Exception) {
                Logger.e("Router", "$cmd failed: ${e.message}")
                resp(id, 500, "${e.javaClass.simpleName}: ${e.message}", JSONObject.NULL)
            } catch (t: Throwable) {
                // Error/OOM 也兜住：保证客户端总能拿到响应，处理线程不悄悄死掉
                Logger.e("Router", "$cmd fatal: ${t.javaClass.simpleName}: ${t.message}")
                resp(id, 500, "${t.javaClass.simpleName}: ${t.message}",
                    JSONObject().put("errCode", "DEVICE_ERROR")
                        .put("hint", "指令执行异常（已捕获，服务继续运行）"))
            }
            onResult(result)
        }
        if (h.async(params)) thread(name = "cmd-$cmd") { run() } else run()
    }

    fun resp(id: String, code: Int, msg: String, data: Any): JSONObject = JSONObject().apply {
        put("id", id); put("code", code); put("msg", msg); put("data", data)
    }

    fun commands(): List<String> = handlers.keys.sorted()
}
