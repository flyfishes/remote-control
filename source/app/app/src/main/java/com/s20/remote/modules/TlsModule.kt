package com.s20.remote.modules

import android.content.Context
import com.s20.remote.core.CommandRouter
import com.s20.remote.net.TlsManager
import org.json.JSONObject

/** HTTPS 证书管理指令 */
object TlsModule {

    fun register(ctx: Context) {
        CommandRouter.register("cert.info") { TlsManager.info(ctx) }
        CommandRouter.register("cert.regen") { p ->
            val extra = mutableListOf<String>()
            p.optJSONArray("dns")?.let { arr ->
                for (i in 0 until arr.length()) extra.add(arr.optString(i))
            }
            TlsManager.invalidate()
            TlsManager.generate(ctx, extra).put("restartRequired", true)
        }
        CommandRouter.register("cert.import") { p ->
            val path = p.optString("path")
            if (path.isEmpty()) {
                JSONObject().put("ok", false).put("error", "missing path")
            } else {
                TlsManager.invalidate()
                TlsManager.import(ctx, path, p.optString("keyPath").ifEmpty { null },
                    p.optString("pass").ifEmpty { null }).put("restartRequired", true)
            }
        }
        CommandRouter.register("cert.pem") {
            JSONObject().put("pem", TlsManager.certPem(ctx) ?: JSONObject.NULL)
        }
    }
}
