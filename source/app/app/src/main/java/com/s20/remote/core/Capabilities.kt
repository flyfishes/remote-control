package com.s20.remote.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/**
 * 设备能力模型：启动时探测一次并缓存，供各模块在执行前分流
 *   root=true  -> 走原 root 实现（S20 主路径，行为不变）
 *   root=false -> 走各模块的公共权限回退（fallbackXxx），做不到则返回
 *                 {ok:false, errCode:NEED_ROOT/NEED_PERM/NOT_SUPPORTED, hint:"..."}
 *
 * 调试：Config.simulateNoRoot=true 时强制视为无 root（验证降级路径）。
 */
object Capabilities {
    private const val TAG = "Caps"

    private lateinit var appCtx: Context

    /** 探测结果缓存 */
    @Volatile private var probed: JSONObject? = null
    @Volatile private var probedAt = 0L
    private val TTL = 30_000L

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun ctx(): Context = appCtx

    private fun perm(granted: Boolean, name: String): JSONObject =
        JSONObject().put("granted", granted).put("permission", name)

    /** 是否具备 root（su 可用且 uid=0）；simulateNoRoot 时恒 false */
    fun root(): Boolean = if (Config.simulateNoRoot) false else RootShell.available()

    /** 是否安装在系统 priv-app 分区（特权权限生效的前提）。
     *  兼容 Magisk 模块挂载（sourceDir 含 .../priv-app/...）与原生 priv-app */
    fun privileged(): Boolean = try {
        val pm = appCtx.packageManager
        val ai = pm.getPackageInfo(appCtx.packageName, 0).applicationInfo
        val paths = listOfNotNull(ai?.sourceDir, ai?.publicSourceDir)
        paths.any { it != null && it.contains("/priv-app/") }
    } catch (e: Exception) { false }

    fun samsung(): Boolean =
        Build.MANUFACTURER.contains("samsung", ignoreCase = true)

    /** 运行时权限查询 */
    fun hasPerm(permission: String): Boolean = try {
        appCtx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    /** MANAGE_EXTERNAL_STORAGE（所有文件访问） */
    fun manageStorage(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else true
    } catch (e: Exception) { false }

    /** 设备管理员已激活（无 root 熄屏用） */
    fun deviceAdmin(): Boolean = try {
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.isAdminActive(android.content.ComponentName(appCtx, com.s20.remote.service.AdminReceiver::class.java))
    } catch (e: Exception) { false }

    /** 完整探测（带缓存）。recheck=true 强制重测。 */
    fun probe(recheck: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        if (!recheck) {
            probed?.let { if (now - probedAt < TTL) return it }
        }
        val o = JSONObject()
        o.put("root", root())
        o.put("privileged", privileged())
        o.put("samsung", samsung())
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("model", Build.MODEL)
        o.put("androidVersion", Build.VERSION.RELEASE)
        o.put("sdkInt", Build.VERSION.SDK_INT)
        o.put("deviceAdmin", deviceAdmin())
        o.put("manageStorage", manageStorage())
        o.put("simulateNoRoot", Config.simulateNoRoot)
        val perms = JSONObject()
        for ((k, p) in mapOf(
            "sms" to android.Manifest.permission.SEND_SMS,
            "smsRead" to android.Manifest.permission.READ_SMS,
            "phone" to android.Manifest.permission.READ_PHONE_STATE,
            "location" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "notifications" to android.Manifest.permission.POST_NOTIFICATIONS,
            "camera" to android.Manifest.permission.CAMERA,
            "mic" to android.Manifest.permission.RECORD_AUDIO,
            "bluetooth" to if (Build.VERSION.SDK_INT >= 31)
                android.Manifest.permission.BLUETOOTH_CONNECT else android.Manifest.permission.BLUETOOTH,
        )) perms.put(k, perm(hasPerm(p), p))
        o.put("perms", perms)

        // 功能级可用性：ok=完整可用 / degraded=降级可用 / no=不可用（reason 说明）
        val f = JSONObject()
        val hasRoot = o.getBoolean("root")
        f.put("hotspot", if (hasRoot && o.getBoolean("samsung")) "ok" else "no")
        f.put("screenshot", if (hasRoot) "ok" else "no")
        f.put("reboot", if (hasRoot) "ok" else "no")
        f.put("unlock", if (hasRoot) "ok" else "no")
        f.put("screenOff", if (hasRoot || o.getBoolean("deviceAdmin")) "ok" else "no")
        f.put("switchSet", if (hasRoot) "ok" else if (Build.VERSION.SDK_INT <= 28) "degraded" else "degraded")
        f.put("files", if (hasRoot) "ok" else if (o.getBoolean("manageStorage")) "degraded" else "no")
        f.put("apps", if (hasRoot) "ok" else "degraded")
        f.put("dataSet", if (hasRoot) "ok" else "no")
        val camOk = perms.optJSONObject("camera")?.optBoolean("granted") == true
        val micOk = perms.optJSONObject("mic")?.optBoolean("granted") == true
        f.put("photo", if (camOk) "ok" else if (hasRoot) "degraded" else "no")
        f.put("record", if (micOk) "ok" else if (hasRoot) "degraded" else "no")
        o.put("features", f)

        probed = o
        probedAt = now
        Logger.d(TAG, "probe: root=${o.getBoolean("root")} priv=${o.getBoolean("privileged")} " +
                "sdk=${o.getInt("sdkInt")} admin=${o.getBoolean("deviceAdmin")}")
        return o
    }

    // ---------- 统一错误返回 ----------

    /** 功能不可用（需要 root / 权限 / 机型不支持），页面据此置灰与提示 */
    fun denied(errCode: String, hint: String, feature: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("errCode", errCode)
            .put("hint", hint)
            .put("feature", feature)

    fun needRoot(feature: String): JSONObject =
        denied("NEED_ROOT", "该功能需要 root（Magisk 授权）才能执行", feature)

    fun needPerm(hint: String, feature: String): JSONObject =
        denied("NEED_PERM", hint, feature)

    fun notSupported(hint: String, feature: String): JSONObject =
        denied("NOT_SUPPORTED", hint, feature)

    /** 从模块返回值里判断是否为"能力拒绝"（供 UI 上浮 hint） */
    fun isDenied(o: JSONObject): Boolean =
        o.optBoolean("ok", true) == false && o.has("errCode")
}
