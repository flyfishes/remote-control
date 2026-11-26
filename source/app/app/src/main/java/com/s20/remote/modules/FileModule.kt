package com.s20.remote.modules

import android.os.Environment
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * 文件操作：root 全局访问。
 * 普通路径用 java.io（/sdcard 有 MANAGE_EXTERNAL_STORAGE），
 * 受限路径（/data 等）自动回退 root 命令。
 */
object FileModule {
    private const val MAX_INLINE = 2 * 1024 * 1024 // 2MB 内嵌返回，更大走 HTTP 下载

    /** 公共路径失败且无 root：结构化返回（区分缺"所有文件访问"权限与需要 root） */
    private fun denied(feature: String): JSONObject {
        return if (com.s20.remote.core.Capabilities.manageStorage())
            com.s20.remote.core.Capabilities.needRoot(feature)
        else com.s20.remote.core.Capabilities.needPerm(
            "无 root 时文件操作需要「所有文件访问」权限（App 内能力面板可一键去授权）", feature)
    }

    fun register() {
        CommandRouter.register("file.list") { p -> list(p) }
        CommandRouter.register("file.read") { p -> read(p) }
        CommandRouter.register("file.write") { p -> write(p) }
        CommandRouter.register("file.delete") { p -> delete(p) }
        CommandRouter.register("file.mkdir") { p -> mkdir(p) }
        CommandRouter.register("file.move") { p -> move(p) }
        CommandRouter.register("file.copy") { p -> copy(p) }
        CommandRouter.register("file.info") { p -> info(p) }
    }

    fun list(p: JSONObject): JSONObject {
        // 规范化：相对路径锚定到 /sdcard/，避免被 process cwd 解析导致逐级浏览错乱
        var path = p.optString("path", "/sdcard/").ifEmpty { "/sdcard/" }
        if (!path.startsWith("/")) path = "/sdcard/" + path.trimStart('/')
        val showHidden = p.optBoolean("hidden", false)
        val f = File(path)
        val arr = JSONArray()
        if (f.isDirectory && f.canRead()) {
            val files = f.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            if (files != null) for (c in files) {
                if (!showHidden && c.name.startsWith(".")) continue
                arr.put(entry(c))
            }
        } else {
            if (!RootShell.available()) return denied("file.list")
            // root 回退：ls -la
            val r = RootShell.exec("ls -la '${path.trimEnd('/')}' 2>&1")
            if (!r.ok) throw IllegalStateException("cannot list: ${r.stderr}")
            val lines = r.stdout.lines().drop(1) // total 行
            for (line in lines) {
                if (line.isBlank()) continue
                // toybox ls -la：8 列（日期+时间各一列）；GNU ls：9 列
                val t = line.trim().split(Regex("\\s+"), 9)
                if (t.size < 8) continue
                val dir = t[0].startsWith("d")
                val name: String
                val mtime: String
                if (t.size >= 9) {
                    name = t[8]; mtime = "${t[5]} ${t[6]} ${t[7]}"
                } else {
                    name = t[7]; mtime = "${t[5]} ${t[6]}"
                }
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue
                arr.put(JSONObject()
                    .put("name", name)
                    .put("dir", dir)
                    .put("size", if (dir) 0 else t[4].toLongOrNull() ?: 0)
                    .put("mtime", mtime)
                    .put("path", path.trimEnd('/') + "/" + name)
                )
            }
        }
        val o = JSONObject().put("path", path).put("count", arr.length()).put("entries", arr)
        return o
    }

    private fun entry(f: File): JSONObject = JSONObject()
        .put("name", f.name)
        .put("dir", f.isDirectory)
        .put("size", f.length())
        .put("mtime", f.lastModified())
        .put("path", f.absolutePath)

    fun read(p: JSONObject): JSONObject {
        val path = p.getString("path")
        val offset = p.optLong("offset", 0)
        val length = p.optLong("length", MAX_INLINE.toLong())
        val f = File(path)
        val data: ByteArray
        if (f.canRead()) {
            f.inputStream().use { ins ->
                if (offset > 0) ins.skip(offset)
                val buf = ByteArray(length.toInt().coerceAtMost((MAX_INLINE)))
                val n = ins.read(buf)
                data = if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        } else {
            if (!RootShell.available()) return denied("file.read")
            val r = RootShell.exec("dd if='$path' bs=1 skip=$offset count=${length.coerceAtMost(MAX_INLINE.toLong())} 2>/dev/null | base64", 30_000)
            if (!r.ok) throw IllegalStateException("cannot read (root fallback failed): ${r.stderr}")
            val bytes = try { Base64.getDecoder().decode(r.stdout.replace("\n", "")) } catch (e: Exception) { ByteArray(0) }
            data = bytes
        }
        return JSONObject()
            .put("path", path)
            .put("size", f.length())
            .put("truncated", data.size >= MAX_INLINE)
            .put("dataB64", Base64.getEncoder().encodeToString(data))
    }

    /** params: path, dataB64, append(默认false)。二进制安全，root 写受限路径 */
    fun write(p: JSONObject): JSONObject {
        val path = p.getString("path")
        val data = Base64.getDecoder().decode(p.optString("dataB64", ""))
        val append = p.optBoolean("append", false)
        val f = File(path)
        val parent = f.parentFile
        var usedRoot = false
        if (parent != null && (parent.canWrite() || (f.exists() && f.canWrite()))) {
            if (append) f.appendBytes(data) else f.writeBytes(data)
        } else {
            if (!RootShell.available()) return denied("file.write")
            val b64 = Base64.getEncoder().encodeToString(data)
            val cmd = "echo '$b64' | base64 -d | " + (if (append) "tee -a " else "tee ") + "'$path' >/dev/null"
            val r = RootShell.exec(cmd, 30_000)
            if (!r.ok) throw IllegalStateException("write failed (root): ${r.stderr}")
            usedRoot = true
        }
        Logger.i("File", "write $path (${data.size}B, root=$usedRoot)")
        return JSONObject().put("path", path).put("bytes", data.size).put("root", usedRoot)
    }

    fun delete(p: JSONObject): JSONObject {
        val path = p.getString("path")
        val recursive = p.optBoolean("recursive", false)
        val f = File(path)
        var ok = if (f.isDirectory && recursive) f.deleteRecursively() else f.delete()
        if (!ok) {
            if (!RootShell.available()) return denied("file.delete")
            val cmd = if (recursive && f.isDirectory) "rm -rf '$path'" else "rm -f '$path'"
            val r = RootShell.exec(cmd, 60_000)
            ok = r.ok
            if (!r.ok) throw IllegalStateException("delete failed: ${r.stderr}")
        }
        return JSONObject().put("path", path).put("deleted", ok)
    }

    fun mkdir(p: JSONObject): JSONObject {
        val path = p.getString("path")
        val f = File(path)
        var ok = f.mkdirs()
        if (!ok) {
            if (!RootShell.available()) return denied("file.mkdir")
            val r = RootShell.exec("mkdir -p '$path'")
            ok = r.ok
            if (!r.ok) throw IllegalStateException("mkdir failed: ${r.stderr}")
        }
        return JSONObject().put("path", path).put("created", ok)
    }

    fun move(p: JSONObject): JSONObject {
        val src = p.getString("src"); val dst = p.getString("dst")
        val f = File(src)
        var ok = if (f.canWrite() && File(dst).parentFile?.canWrite() != false) f.renameTo(File(dst)) else false
        if (!ok) {
            if (!RootShell.available()) return denied("file.move")
            val r = RootShell.exec("mv '$src' '$dst'")
            ok = r.ok
            if (!r.ok) throw IllegalStateException("move failed: ${r.stderr}")
        }
        return JSONObject().put("src", src).put("dst", dst).put("moved", ok)
    }

    fun copy(p: JSONObject): JSONObject {
        val src = p.getString("src"); val dst = p.getString("dst")
        val f = File(src)
        if (!RootShell.available()) {
            // 无 root：仅当源可读且目标父目录可写时用 java.io
            val dstF = File(dst)
            if (f.canRead() && dstF.parentFile?.canWrite() == true) {
                (if (f.isDirectory) f.copyRecursively(dstF, overwrite = true) else f.copyTo(dstF, overwrite = true))
                return JSONObject().put("src", src).put("dst", dst).put("copied", true).put("mode", "java-io")
            }
            return denied("file.copy")
        }
        val r = RootShell.exec("cp -r '$src' '$dst'", 60_000)
        if (!r.ok) throw IllegalStateException("copy failed: ${r.stderr}")
        return JSONObject().put("src", src).put("dst", dst).put("copied", r.ok)
    }

    fun info(p: JSONObject): JSONObject {
        val path = p.getString("path")
        val r = RootShell.exec("stat -c '%F|%s|%Y|%a' '$path'")
        val f = File(path)
        val o = JSONObject().put("path", path)
        if (f.exists()) {
            o.put("dir", f.isDirectory).put("size", f.length()).put("mtime", f.lastModified())
                .put("readable", f.canRead()).put("writable", f.canWrite())
        }
        if (r.ok && r.stdout.contains("|")) {
            val t = r.stdout.trim().split("|")
            if (t.size >= 4) o.put("statType", t[0]).put("statSize", t[1].toLongOrNull())
                .put("statMtime", t[2]).put("statPerm", t[3])
        }
        return o
    }
}
