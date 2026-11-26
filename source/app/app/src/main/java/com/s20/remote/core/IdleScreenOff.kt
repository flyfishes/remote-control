package com.s20.remote.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.s20.remote.modules.StatusModule
import org.json.JSONObject

/**
 * 进程级「空闲熄屏」定时器（不依赖 Activity 生命周期）。
 *
 * 背景：开机脚本 auto_unlock 用 input keyevent 点亮屏幕并输入 PIN 解锁，
 * 随后只拉起 MainService（不启动界面）。原实现把空闲判断挂在首页 2s ticker 上
 * （ticker 在 onResume 启动 / onPause 停止），Activity 从未 resume 时判断永远不执行，
 * 于是解锁后屏幕一直亮着。
 *
 * 现改为：进程启动（Application.onCreate）时若开关打开就 arm 一个 30s 定时；
 * 首页触摸/按键调用 [touch] 重新计时；Activity 进入后台时 [disarm]。
 */
object IdleScreenOff {
    private const val TAG = "IdleScreenOff"

    /** 空闲阈值 */
    const val IDLE_MS = 30_000L

    /** 开机窗口：仅开机 5 分钟内进程自启才自动 arm，避免日常进程重启时误熄屏 */
    private const val BOOT_WINDOW_MS = 5 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var armed = false
    @Volatile private var fired = false
    @Volatile private var lastActAt = 0L
    @Volatile private var lastResult = ""
    /** 界面是否在前台（曾经 resume 过） */
    @Volatile private var uiForeground = false
    /** 被前台临时占用（重启倒计时弹窗等），暂不熄屏 */
    @Volatile private var blocked = false

    private val tick = Runnable { check() }

    /** 进程启动时调用：开机窗口内 + 开关打开 -> arm。返回是否已 arm */
    fun armOnProcessStart(): Boolean {
        if (!Config.appIdleScreenOff) return false
        val up = SystemClock.elapsedRealtime()
        if (up > BOOT_WINDOW_MS) {
            Logger.i(TAG, "skip process-start arm (uptime ${up / 1000}s)")
            return false
        }
        arm("process-start")
        return true
    }

    /** 立即开始计时（reason 仅用于日志） */
    fun arm(reason: String) {
        if (!Config.appIdleScreenOff) { disarm(); return }
        armed = true
        fired = false
        lastActAt = SystemClock.elapsedRealtime()
        Logger.i(TAG, "arm ($reason) -> ${IDLE_MS / 1000}s")
        schedule(IDLE_MS)
    }

    fun disarm() {
        if (armed) Logger.i(TAG, "disarm")
        armed = false
        handler.removeCallbacks(tick)
    }

    /** 有触摸/按键：重新计时；已熄过屏则复位，允许下一轮 */
    fun touch() {
        if (!Config.appIdleScreenOff) return
        lastActAt = SystemClock.elapsedRealtime()
        fired = false
        if (armed) schedule(IDLE_MS) else arm("activity")
    }

    /** 开关切换 */
    fun onToggle(enabled: Boolean) {
        if (enabled) arm("toggle") else disarm()
    }

    /**
     * 首页进入前台：arm 并标记界面可见。
     * 注意：若 Activity 只是被创建但从未 resume（开机解锁场景），不会走到这里，
     * 此时进程级 arm 继续有效。
     */
    fun onUiResume() {
        uiForeground = true
        arm("ui-resume")
    }

    /** 首页退到后台：用户已真正离开界面，不再代为熄屏 */
    fun onUiPause() {
        if (uiForeground) {
            uiForeground = false
            disarm()
        }
    }

    /** 临时占用（如重启 5 秒倒计时弹窗），true 期间不触发熄屏 */
    fun setBlocked(b: Boolean) { blocked = b }

    fun status(): JSONObject = JSONObject()
        .put("enabled", Config.appIdleScreenOff)
        .put("armed", armed)
        .put("fired", fired)
        .put("idleMs", if (lastActAt == 0L) 0L else SystemClock.elapsedRealtime() - lastActAt)
        .put("idleSec", IDLE_MS / 1000)
        .put("lastResult", lastResult)

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs.coerceAtLeast(0))
    }

    private fun check() {
        if (!armed || fired) return
        if (!Config.appIdleScreenOff) { disarm(); return }
        // 弹窗等临时占用：稍后重试，不消耗本轮
        if (blocked) { schedule(2_000L); return }
        val idle = SystemClock.elapsedRealtime() - lastActAt
        if (idle < IDLE_MS) { schedule(IDLE_MS - idle); return }
        fired = true
        Logger.i(TAG, "idle ${idle / 1000}s -> screen off")
        Thread({
            try {
                val r = StatusModule.screenOffNow()
                lastResult = "${if (r.optBoolean("ok")) "ok" else "fail"}/${r.optString("method")}/${r.optString("wakefulness")}"
                Logger.i(TAG, "screenOff -> $lastResult")
            } catch (e: Throwable) {
                lastResult = "error/${e.javaClass.simpleName}"
                Logger.w(TAG, "screenOff failed: ${e.message}")
            }
        }, "idle-screenoff").start()
    }
}
