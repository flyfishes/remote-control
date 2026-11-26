package com.s20.remote.modules

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.s20.remote.core.Capabilities
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 应用管理：查询/安装/卸载/启动/关闭（root 走 pm/am，免确认） */
object AppModule {

    fun register(ctx: Context) {
        CommandRouter.register("app.list") { p -> list(ctx, p) }
        CommandRouter.register("app.install") { p -> install(p) }
        CommandRouter.register("app.uninstall") { p -> uninstall(p) }
        CommandRouter.register("app.start") { p -> start(ctx, p) }
        CommandRouter.register("app.stop") { p -> stop(ctx, p) }
        CommandRouter.register("app.running") { runningList(ctx) }
    }

    /** 从 APK 文件解析应用名：禁用/Stub 包 PM 读不到 label，但直接读 APK 资源可行 */
    private fun labelFromApk(pm: PackageManager, apkPath: String, fallback: String): String {
        if (apkPath.isEmpty()) return fallback
        try {
            val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return fallback
            val ai = info.applicationInfo ?: return fallback
            // loadLabel 依赖 sourceDir 指向真实 APK，否则回退到包名
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            val label = ai.loadLabel(pm).toString().trim()
            return if (label.isNotEmpty() && label != fallback) label else fallback
        } catch (_: Throwable) { return fallback }
    }

    /** params: system(含系统应用), keyword
     *  双路合并：PackageManager（有 QUERY_ALL_PACKAGES）+ root pm list（含禁用/未启动包）
     */
    fun list(ctx: Context, p: JSONObject): JSONObject {
        val pm = ctx.packageManager
        val keyword = p.optString("keyword", "").lowercase()
        val includeSystem = p.optBoolean("system", false)
        val map = LinkedHashMap<String, JSONObject>()

        // 1) PackageManager 路径（label/版本完整）
        try {
            for (ai in pm.getInstalledPackages(0)) {
                val isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = try { ai.applicationInfo.loadLabel(pm).toString() } catch (_: Throwable) { ai.packageName }
                map[ai.packageName] = JSONObject()
                    .put("pkg", ai.packageName).put("label", label).put("system", isSystem)
                    .put("versionName", ai.versionName ?: "").put("versionCode", ai.longVersionCode)
                    .put("apkPath", ai.applicationInfo.sourceDir)
            }
        } catch (e: Throwable) {
            Logger.w("App", "pm list failed: ${e.message}")
        }

        // 2) root pm 路径：补全被禁用/未启动/可见性受限的包（无 root 时跳过， PackageManager 结果已可用）
        if (!RootShell.available()) {
            return finishList(map, keyword, includeSystem)
        }
        val r = RootShell.exec("pm list packages -f", 30_000)
        val missing = mutableListOf<Pair<String, String>>() // pkg -> apkPath
        for (line in r.stdout.lines()) {
            val m = Regex("^package:(.*)=(.*)$").find(line.trim()) ?: continue
            val apkPath = m.groupValues[1]
            val pkg = m.groupValues[2]
            if (map.containsKey(pkg)) continue
            val isSystem = apkPath.startsWith("/system/") || apkPath.startsWith("/product/") ||
                    apkPath.startsWith("/vendor/") || apkPath.startsWith("/system_ext/")
            if (isSystem && !includeSystem) continue
            missing.add(pkg to apkPath)
        }
        // 批量拿版本名：dumpsys 一次输出，避免逐包调用（禁用包 PM 读不到，dumpsys 可以）
        val verMap = HashMap<String, String>()
        if (missing.isNotEmpty()) {
            val dump = RootShell.exec("dumpsys package 2>/dev/null | grep -E 'Package \\[|versionName='", 40_000)
            var curPkg = ""
            for (line in dump.stdout.lines()) {
                val t = line.trim()
                if (t.startsWith("Package [")) {
                    curPkg = Regex("Package \\[([^]]+)\\]").find(t)?.groupValues?.get(1)
                        ?.split(" ")?.firstOrNull() ?: ""
                } else if (t.startsWith("versionName=") && curPkg.isNotEmpty()) {
                    verMap[curPkg] = t.substringAfter("versionName=")
                    curPkg = ""
                }
            }
        }
        for ((pkg, apkPath) in missing) {
            val ver = verMap[pkg]
                ?: try { pm.getPackageInfo(pkg, 0).versionName ?: "" } catch (_: Throwable) { "" }
            // 从 APK 文件解析应用名：禁用/Stub 包 PM 读不到 label，但直接读 APK 资源可行
            val label = labelFromApk(pm, apkPath, pkg)
            map[pkg] = JSONObject()
                .put("pkg", pkg).put("label", label).put("system", false)
                .put("versionName", ver).put("versionCode", 0L).put("apkPath", apkPath)
        }

        return finishList(map, keyword, includeSystem)
    }

    /** 关键字过滤 + 排序输出 */
    private fun finishList(
        map: LinkedHashMap<String, JSONObject>, keyword: String, includeSystem: Boolean
    ): JSONObject {
        val arr = JSONArray()
        map.values
            .filter { o ->
                if (keyword.isEmpty()) true
                else o.optString("label").lowercase().contains(keyword) ||
                        o.optString("pkg").lowercase().contains(keyword)
            }
            .sortedBy { it.optString("label").lowercase() }
            .forEach { arr.put(it) }
        return JSONObject().put("count", arr.length()).put("apps", arr)
            .put("mode", if (RootShell.available()) "root+pm" else "packagemanager")
    }

    /** params: path(apk在手机上的路径) */
    fun install(p: JSONObject): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("app.install")
        val path = p.getString("path")
        val f = File(path)
        if (!f.exists()) throw IllegalArgumentException("apk not found: $path")
        val r = RootShell.exec("pm install -r -g '$path'", 120_000)
        val o = JSONObject()
        o.put("ok", r.ok)
        o.put("output", (r.stdout + " " + r.stderr).trim())
        if (r.ok) Logger.i("App", "installed $path") else Logger.w("App", "install failed: ${r.stderr}")
        return o
    }

    /** params: pkg, keepData(默认false) */
    fun uninstall(p: JSONObject): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("app.uninstall")
        val pkg = p.getString("pkg")
        val cmd = if (p.optBoolean("keepData")) "pm uninstall -k '$pkg'" else "pm uninstall '$pkg'"
        val r = RootShell.exec(cmd, 60_000)
        val o = JSONObject()
        o.put("ok", r.ok)
        o.put("output", (r.stdout + " " + r.stderr).trim())
        return o
    }

    /** params: pkg 或 pkg+activity */
    fun start(ctx: Context, p: JSONObject): JSONObject {
        val pkg = p.getString("pkg").trim()
        val activity = p.optString("activity", "").trim()

        // root 优先：`am start` 以 shell 身份启动，不受 Android 10+ 后台启动限制。
        // 旧实现即便有 root 也走 ctx.startActivity()（Service 上下文无界面栈），会被系统静默拦截，
        // 表现为网页点「启」日志显示成功但应用没起来。
        if (RootShell.available()) {
            val comp = if (activity.isNotEmpty()) normalizeComp(pkg, activity) else resolveLauncher(ctx, pkg)
            if (comp == null && activity.isEmpty()) {
                // 先确认包是否真实存在，避免把 monkey 的调试输出当成错误原因
                val ex = RootShell.exec("pm path '$pkg'", 15_000)
                if (ex.stdout.isBlank()) {
                    return JSONObject().put("ok", false).put("output", ex.stdout.trim())
                        .put("method", "none").put("component", "")
                        .put("errCode", "DEVICE_ERROR").put("feature", "app.start")
                        .put("hint", "包不存在或已卸载：$pkg")
                }
            }
            val r = if (comp != null) {
                RootShell.exec("am start -n '$comp'", 20_000)
            } else {
                // 有包但没有 LAUNCHER Activity（少见）：退回 monkey
                RootShell.exec("monkey -p '$pkg' -c android.intent.category.LAUNCHER 1", 20_000)
            }
            val out = (r.stdout + " " + r.stderr).trim()
            val bad = !r.ok || out.contains("Error") || out.contains("does not exist") ||
                    out.contains("No activities found") || out.contains("Exception")
            val o = JSONObject().put("ok", !bad).put("output", out)
                .put("method", if (comp != null) "am start" else "monkey")
                .put("component", comp ?: "")
            if (bad) {
                val hint = when {
                    out.contains("No activities found") -> "该应用没有可启动的 LAUNCHER Activity：$pkg"
                    out.contains("does not exist") || out.contains("Error type 3") -> "Activity 不存在：${comp ?: pkg}"
                    out.contains("Permission Denial") || out.contains("SecurityException") -> "权限被拒绝：${out.take(200)}"
                    out.isEmpty() -> "无输出（退出码 ${r.exitCode}）"
                    else -> out.take(300)
                }
                o.put("errCode", "DEVICE_ERROR").put("feature", "app.start").put("hint", hint)
            }
            return o
        }

        // 无 root：只对自身或受豁免的包有效，其余会被后台启动限制拦截
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Capabilities.needRoot("app.start")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            JSONObject().put("ok", true).put("method", "startActivity").put("output", "")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("errCode", "NEED_ROOT").put("feature", "app.start")
                .put("output", e.message ?: "")
                .put("hint", "无 root：后台启动 Activity 被系统拦截（${e.javaClass.simpleName}），请在手机端打开该应用")
        }
    }

    /** 解析包名 + Activity 片段 为 am start 可用的 component（pkg/cls） */
    private fun normalizeComp(pkg: String, activity: String): String {
        if (activity.contains("/")) return activity
        val cls = if (activity.startsWith(".")) "$pkg$activity" else activity
        return "$pkg/$cls"
    }

    /** 用 PackageManager 找 LAUNCHER Activity，返回 pkg/cls；找不到返回 null */
    private fun resolveLauncher(ctx: Context, pkg: String): String? {
        val pm = ctx.packageManager
        try {
            pm.getLaunchIntentForPackage(pkg)?.component?.let { return "${it.packageName}/${it.className}" }
        } catch (_: Exception) {}
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val list = if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(i, 0)
            list.firstOrNull()?.activityInfo?.let { return "${it.packageName}/${it.name}" }
        } catch (e: Exception) {
            Logger.w("AppModule", "resolveLauncher($pkg): ${e.message}")
        }
        return null
    }

    /** params: pkg（force-stop） */
    fun stop(ctx: Context, p: JSONObject): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("app.stop")
        val pkg = p.getString("pkg")
        ctx.packageManager.getPackageInfo(pkg, 0) // 校验存在
        val r = RootShell.exec("am force-stop '$pkg'")
        val o = JSONObject().put("ok", r.ok).put("output", (r.stdout + " " + r.stderr).trim())
        return o
    }

    fun runningList(ctx: Context): JSONObject {
        if (!RootShell.available()) return Capabilities.needRoot("app.running")
        val r = RootShell.exec("ps -A -o PID,USER,NAME | grep -v '^\\s*PID'")
        val arr = JSONArray()
        for (line in r.stdout.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size >= 3) {
                val name = t[2]
                if (name.startsWith("com.") || name.contains(":")) {
                    arr.put(JSONObject().put("pid", t[0]).put("user", t[1]).put("name", name))
                }
            }
        }
        return JSONObject().put("count", arr.length()).put("processes", arr)
    }
}
