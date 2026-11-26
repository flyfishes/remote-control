package com.s20.remote.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 锁屏 PIN ↔ magisk 开机脚本同步。
 *
 * 开机脚本（/data/adb/modules/s20remote/service.sh）本身会优先从 DE 区
 * s20_remote.xml 读取 unlockPin，这里再显式改写脚本里的 UNLOCK_PIN 行，
 * 保证「改 PIN 即改开机脚本」，即使读取 xml 失败也有正确的兜底值。
 */
object PinBoot {
    private const val TAG = "PinBoot"

    private val cands = listOf(
        "/data/adb/modules/s20remote/service.sh",
        "/data/adb/service.d/s20remote.sh"
    )

    fun sync(pin: String): JSONObject {
        val out = JSONObject()
        if (!Regex("^\\d{4,16}$").matches(pin)) {
            return out.put("ok", false).put("error", "PIN 必须为 4-16 位数字")
        }
        val done = JSONArray()
        val bad = JSONArray()
        for (path in cands) {
            val exists = RootShell.exec("[ -f '$path' ] && echo yes", 5_000).stdout.contains("yes")
            if (!exists) continue
            RootShell.exec("sed -i 's|^UNLOCK_PIN=.*|UNLOCK_PIN=\"$pin\"|' '$path'", 8_000)
            val got = RootShell.exec("grep -m1 '^UNLOCK_PIN=' '$path'", 5_000).stdout.trim()
            if (got.contains("\"$pin\"")) done.put(path) else bad.put("$path => $got")
        }
        if (done.length() == 0 && bad.length() == 0) {
            Logger.w(TAG, "boot script not found (magisk module missing?)")
            return out.put("ok", false).put("updated", done).put("failed", bad)
                .put("error", "未找到开机脚本（/data/adb/modules/s20remote/service.sh）")
        }
        Logger.i(TAG, "pin synced: $done")
        return out.put("ok", bad.length() == 0).put("updated", done).put("failed", bad)
    }
}
