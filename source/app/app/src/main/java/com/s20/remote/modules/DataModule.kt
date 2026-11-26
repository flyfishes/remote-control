package com.s20.remote.modules

import com.s20.remote.core.Capabilities
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.RootShell
import org.json.JSONObject

/**
 * 移动数据开关。
 * 双路径：
 *  - 读状态：公共 API TelephonyManager.isDataEnabled（26+），无 root 也真实
 *  - 写开关：仅 root（svc data），无公共 API
 */
object DataModule {

    fun register() {
        CommandRouter.register("data.get") { get() }
        CommandRouter.register("data.set") { set(it) }
    }

    fun get(): JSONObject {
        val on = try {
            val tm = com.s20.remote.App.ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= 26) tm.isDataEnabled
            else RootShell.execSoft("settings get global mobile_data").stdout.trim() == "1"
        } catch (e: Exception) {
            RootShell.execSoft("settings get global mobile_data").stdout.trim() == "1"
        }
        val tele = RootShell.execSoft("dumpsys telephony.registry | grep -m1 mDataConnectionState")
        return JSONObject().put("mobileData", on)
            .put("connStateRaw", tele.stdout.trim())
    }

    fun set(p: JSONObject): JSONObject {
        val on = p.optBoolean("on", false)
        if (!RootShell.available()) {
            return Capabilities.needRoot("data.set")
        }
        val r = RootShell.exec("svc data ${if (on) "enable" else "disable"}")
        val o = JSONObject().put("ok", r.ok).put("on", on)
        if (!r.ok) o.put("error", r.stderr)
        return o
    }
}
