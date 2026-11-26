package com.s20.remote.modules

import android.content.Context
import android.telephony.SmsManager
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短信模块：读取全部 / 检索 / 发送 / 删除（标准 provider 路径）。
 * 开机锁屏阶段 CE 区未挂载，provider 不可用；由开机脚本模拟输入密码解锁，
 * 解锁后 CE 区挂载，provider 即可正常读写。
 */
object SmsModule {
    private const val URI = "content://sms"

    fun register(ctx: Context) {
        CommandRouter.register("sms.list") { p -> list(ctx, p) }
        CommandRouter.register("sms.send") { p -> send(p) }
        CommandRouter.register("sms.delete") { p -> delete(ctx, p) }
    }

    private fun appCtxSend(): Context? = try { com.s20.remote.App.ctx } catch (_: Exception) { null }

    /** 短信权限检查（运行时权限，与 root 无关） */
    private fun permGuard(ctx: Context, send: Boolean, feature: String): JSONObject? {
        val need = if (send) android.Manifest.permission.SEND_SMS else android.Manifest.permission.READ_SMS
        return if (ctx.checkSelfPermission(need) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            com.s20.remote.core.Capabilities.needPerm("缺少短信权限（${need.substringAfterLast(".")}），请在系统设置或 App 能力面板授权", feature)
        else null
    }

    /** params: limit(默认100), offset, address, keyword, startTs, endTs, order(asc/desc默认desc) */
    fun list(ctx: Context, p: JSONObject): JSONObject {
        permGuard(ctx, false, "sms.list")?.let { return it }
        val limit = p.optInt("limit", 100)
        val offset = p.optInt("offset", 0)
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        if (p.has("address")) { where.append(" AND address LIKE ?"); args.add("%${p.getString("address")}%") }
        if (p.has("keyword")) { where.append(" AND body LIKE ?"); args.add("%${p.getString("keyword")}%") }
        if (p.has("startTs")) { where.append(" AND date >= ?"); args.add(p.getLong("startTs").toString()) }
        if (p.has("endTs")) { where.append(" AND date <= ?"); args.add(p.getLong("endTs").toString()) }
        val order = if (p.optString("order", "desc") == "asc") "ASC" else "DESC"

        val cols = arrayOf("_id", "thread_id", "address", "person", "date", "type", "read", "status", "body")
        val sort = "date $order LIMIT $limit OFFSET $offset"
        val rows = JSONArray()
        val cr = ctx.contentResolver
        cr.query(android.net.Uri.parse(URI), cols, where.toString(), args.toTypedArray(), sort)?.use { c ->
            while (c.moveToNext()) rows.put(rowFromCursor(c))
        } ?: throw IllegalStateException("sms provider unavailable (device locked?)")

        val o = JSONObject()
        o.put("count", rows.length())
        o.put("messages", rows)
        o.put("source", "provider")
        return o
    }

    private fun rowFromCursor(c: android.database.Cursor): JSONObject = JSONObject()
        .put("id", c.getLong(0)).put("threadId", c.getLong(1))
        .put("address", c.getString(2) ?: "").put("date", c.getLong(4))
        .put("type", c.getInt(5)).put("read", c.getInt(6))
        .put("body", c.getString(8) ?: "")

    fun send(p: JSONObject): JSONObject {
        appCtxSend()?.let { ctx -> permGuard(ctx, true, "sms.send")?.let { return it } }
        val address = p.optString("address", "").trim()
        val text = p.optString("text", "")
        if (address.isEmpty()) throw IllegalArgumentException("address 不能为空")
        if (text.isEmpty()) throw IllegalArgumentException("text 不能为空")
        val sm = SmsManager.getDefault() ?: throw IllegalStateException("SmsManager 不可用")
        val parts = sm.divideMessage(text)
        val o = JSONObject().put("parts", parts.size).put("address", address)
        try {
            if (parts.size == 1) {
                sm.sendTextMessage(address, null, text, null, null)
            } else {
                sm.sendMultipartTextMessage(address, null, parts, null, null)
            }
            o.put("sent", true)
            Logger.i("Sms", "sent to $address (${parts.size} part(s))")
        } catch (e: Exception) {
            o.put("sent", false).put("error", e.message ?: e.toString())
            Logger.e("Sms", "send to $address failed: ${e.message}")
        }
        return o
    }

    /** 供内部（流量告警等）复用：发送并返回是否成功 */
    fun sendText(address: String, text: String): Boolean {
        return try {
            val sm = SmsManager.getDefault() ?: return false
            val parts = sm.divideMessage(text)
            if (parts.size == 1) sm.sendTextMessage(address, null, text, null, null)
            else sm.sendMultipartTextMessage(address, null, parts, null, null)
            true
        } catch (e: Exception) {
            Logger.e("Sms", "sendText $address failed: ${e.message}")
            false
        }
    }

    /** params: ids=[..] 或 threadIds=[..] 或 all=true（慎用） */
    fun delete(ctx: Context, p: JSONObject): JSONObject {
        permGuard(ctx, false, "sms.delete")?.let { return it }
        val cr = ctx.contentResolver
        val o = JSONObject()
        var deleted = 0
        if (p.has("ids")) {
            val ids = p.getJSONArray("ids")
            for (i in 0 until ids.length()) {
                deleted += cr.delete(android.net.Uri.parse("$URI/${ids.getLong(i)}"), null, null)
            }
        } else if (p.has("threadIds")) {
            val tids = p.getJSONArray("threadIds")
            for (i in 0 until tids.length()) {
                deleted += cr.delete(android.net.Uri.parse("content://sms/conversations/${tids.getLong(i)}"), null, null)
            }
        } else if (p.optBoolean("all")) {
            deleted = cr.delete(android.net.Uri.parse(URI), null, null)
        } else {
            throw IllegalArgumentException("需要 ids / threadIds / all=true")
        }
        o.put("deleted", deleted)
        Logger.i("Sms", "deleted $deleted")
        return o
    }
}
