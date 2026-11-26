package com.s20.remote.modules

import android.content.Context
import android.content.Intent
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import org.json.JSONArray
import org.json.JSONObject

/** 配置读写 / 日志查询 / 服务重启（配置变更后调用） */
object ConfigModule {

    fun register(ctx: Context) {
        CommandRouter.register("config.get") { Config.dump() }
        CommandRouter.register("config.set") { p -> set(ctx, p) }
        CommandRouter.register("log.get") { p -> getLog(p) }
        CommandRouter.register("log.clear") { Logger.clear(); JSONObject().put("cleared", true) }
        CommandRouter.register("service.restart") { restart(ctx) }
        CommandRouter.register("cmd.list") { JSONObject().put("cmds", JSONArray(CommandRouter.commands())) }
    }

    private fun set(ctx: Context, p: JSONObject): JSONObject {
        val updatedKeys = mutableListOf<String>()
        val failed = JSONArray()
        val keys = p.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (Config.setJson(k, p.get(k))) updatedKeys.add(k) else failed.put(k)
        }
        val updated = JSONArray(updatedKeys)
        val o = JSONObject()
            .put("updated", updated)
            .put("failed", failed)
            .put("needRestart", updatedKeys.any {
                it in listOf("httpPort", "relayUrl", "relayEnabled", "tlsEnabled", "tlsMode")
            })
        // 修改锁屏 PIN：同步写入 magisk 开机脚本（脚本开机时也读 DE 配置，这里再显式落一份，双保险）
        if (updatedKeys.contains("unlockPin")) {
            o.put("pinSync", com.s20.remote.core.PinBoot.sync(Config.unlockPin))
        }
        // relay 配置变化即时生效
        if (updatedKeys.contains("relayEnabled") || updatedKeys.contains("relayUrl")) {
            Logger.i("Config", "relay config changed, restarting relay")
            val svc = ctx.getSharedPreferences("s20_relay_ctl", 0)
            svc.edit().putLong("ts", System.currentTimeMillis()).apply()
            ctx.sendBroadcast(Intent("com.s20.remote.RESTART_RELAY"))
        }
        return o
    }

    private fun getLog(p: JSONObject): JSONObject {
        val n = p.optInt("lines", 200)
        val o = JSONObject()
            .put("enabled", Config.logEnabled)
            .put("file", Config.logFile)
            .put("lines", JSONArray(com.s20.remote.core.Logger.recent(n)))
        // 同时返回磁盘日志内容（若指定 disk=true 则返回全部落盘行）
        val disk = com.s20.remote.core.Logger.readFile()
        o.put("diskSize", disk.length)
        if (p.optBoolean("disk", false)) {
            o.put("diskLines", JSONArray(disk.lines()))
        }
        return o
    }

    private fun restart(ctx: Context): JSONObject {
        ctx.stopService(Intent(ctx, com.s20.remote.service.MainService::class.java))
        Thread.sleep(500)
        com.s20.remote.core.Compat.startForegroundCompat(
            ctx, Intent(ctx, com.s20.remote.service.MainService::class.java))
        return JSONObject().put("restarted", true)
    }
}
