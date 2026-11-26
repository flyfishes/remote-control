package com.s20.remote.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 日志：Logcat + 内存环形缓冲(500条) + 可选文件落盘（logEnabled 开关控制） */
object Logger {
    private const val TAG = "S20Remote"
    private const val MAX_MEM = 500
    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val lock = Any()

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    private fun log(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        Log.println(when (level) { "D" -> Log.DEBUG; "I" -> Log.INFO; "W" -> Log.WARN; else -> Log.ERROR }, TAG, msg)
        synchronized(lock) {
            mem.addLast(line)
            if (mem.size > MAX_MEM) mem.removeFirst()
        }
        if (Config.logEnabled) appendFile(line)
    }

    private fun appendFile(line: String) = try {
        val f = File(Config.logFile)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        f.appendText(line + "\n")
    } catch (_: Exception) {}

    fun recent(lines: Int = 200): List<String> = synchronized(lock) { mem.toList().takeLast(lines) }

    /** 清空内存缓冲 + 磁盘日志文件（log.clear 指令调用） */
    fun clear() {
        synchronized(lock) { mem.clear() }
        try {
            val f = File(Config.logFile)
            if (f.exists()) f.writeText("")
        } catch (_: Exception) {}
    }

    /** 读取磁盘日志文件全部内容（供下载/拉取） */
    fun readFile(): String = try {
        val f = File(Config.logFile)
        if (f.exists()) f.readText() else ""
    } catch (_: Exception) { "" }
}
