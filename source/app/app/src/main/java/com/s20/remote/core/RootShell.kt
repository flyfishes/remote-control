package com.s20.remote.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ShellResult(val ok: Boolean, val stdout: String, val stderr: String, val exitCode: Int)

/**
 * 常驻 su shell 执行器。首次调用触发 Magisk 授权。
 * 单一流合并方案: (cmd) 2>&1; echo MARKER:rc
 * 无临时文件依赖，带超时保护，串行执行（进程内互斥）。
 *
 * 加固：
 *  - root 可用性缓存（可用 10min / 不可用 30s），避免 su 不存在时每次调用都 spawn 进程、反复弹授权
 *  - 已知不可用时 exec 快速失败（stderr=root unavailable，模块据此返回 NEED_ROOT）
 *  - execSoft：忙时立即返回失败（供心跳等可跳过的读命令），不阻塞 60s 级长操作
 *  - Config.simulateNoRoot=true 时视为无 root（降级路径测试）
 */
object RootShell {
    private const val TAG = "RootShell"
    private val seq = AtomicLong(0)
    private val lock = ReentrantLock()

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var out: BufferedReader? = null

    // 可用性缓存
    @Volatile private var availCache: Boolean? = null
    @Volatile private var availAt = 0L

    private fun cacheOkTtl() = 10 * 60 * 1000L
    private fun cacheBadTtl() = 30 * 1000L

    /** root 是否可用（带缓存）。force=true 强制重新探测。 */
    fun available(force: Boolean = false): Boolean {
        if (Config.simulateNoRoot) return false
        val c = availCache
        val at = availAt
        if (!force && c != null) {
            val ttl = if (c) cacheOkTtl() else cacheBadTtl()
            if (System.currentTimeMillis() - at < ttl) return c
        }
        val ok = probe()
        availCache = ok
        availAt = System.currentTimeMillis()
        return ok
    }

    /** 清缓存，下次调用重新探测 */
    fun recheck() { availCache = null }

    private fun probe(): Boolean = lock.withLock {
        try {
            ensureShell()
            val r = execRaw("id -u", 10_000)
            r.ok && r.stdout.trim() == "0"
        } catch (e: Exception) {
            Logger.w(TAG, "root probe failed: ${e.message}")
            false
        }
    }

    /** 主入口：root 不可用时快速失败，不再 spawn su */
    fun exec(cmd: String, timeoutMs: Long = 15000): ShellResult {
        if (!available()) {
            return ShellResult(false, "", "root unavailable (NEED_ROOT)", -1)
        }
        return lock.withLock { execRaw(cmd, timeoutMs) }
    }

    /** 可跳过的读命令：shell 忙（长操作进行中）时立即失败返回，不排队 */
    fun execSoft(cmd: String, timeoutMs: Long = 8000): ShellResult {
        if (!available()) return ShellResult(false, "", "root unavailable (NEED_ROOT)", -1)
        if (!lock.tryLock()) return ShellResult(false, "", "busy (skipped)", -1)
        return try { execRaw(cmd, timeoutMs) } finally { lock.unlock() }
    }

    private fun execRaw(cmd: String, timeoutMs: Long): ShellResult {
        try {
            ensureShell()
            val id = seq.incrementAndGet()
            val marker = "__S20_RC_$id:"
            writer!!.write("echo __S20_BGN_$id\n")
            writer!!.write("($cmd) 2>&1\n")
            writer!!.write("echo \"${marker}\$?\"\n")
            writer!!.flush()
            val sb = StringBuilder()
            var collecting = false
            var rc = -1
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (out!!.ready()) {
                    val line = out!!.readLine() ?: break
                    if (line.startsWith("__S20_BGN_$id")) { collecting = true; continue }
                    if (line.startsWith(marker)) {
                        rc = line.substringAfter(marker).trim().toIntOrNull() ?: -1
                        return ShellResult(rc == 0, sb.toString().trimEnd('\n'), "", rc)
                    }
                    if (collecting) sb.appendLine(line)
                } else {
                    Thread.sleep(10)
                }
            }
            Logger.w(TAG, "timeout ($timeoutMs ms): ${cmd.take(80)}")
            killShell()
            return ShellResult(false, sb.toString().trimEnd('\n'), "timeout after ${timeoutMs}ms", -1)
        } catch (e: Exception) {
            Logger.w(TAG, "exec failed: ${e.message}")
            killShell()
            // su 进程消失通常意味着授权被撤销，清缓存让下次重新探测
            availCache = null
            return ShellResult(false, "", e.message ?: "unknown", -1)
        }
    }

    private fun ensureShell() {
        // Process.isAlive() 是 API 26+，Android 6.0（乐视 1 Pro）会 NoSuchMethodError
        if (processAlive(proc)) return
        killShell()
        proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh"))
        writer = BufferedWriter(OutputStreamWriter(proc!!.outputStream))
        out = BufferedReader(InputStreamReader(proc!!.inputStream), 65536)
        // 错误流直接丢弃（输出已 2>&1 合并）
        Thread { try { proc!!.errorStream.readBytes() } catch (_: Exception) {} }.start()
        Thread.sleep(200)
    }

    /** 全版本兼容的存活检测：exitValue() 未抛异常=已退出，抛 IllegalThreadStateException=仍在运行 */
    private fun processAlive(p: Process?): Boolean {
        if (p == null) return false
        return try { p.exitValue(); false } catch (e: IllegalThreadStateException) { true }
    }

    private fun killShell() {
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null; writer = null; out = null
    }
}
