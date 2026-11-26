package com.s20.remote.core

import android.content.Context
import android.os.Build

/**
 * 低版本设备兼容工具（minSdk 23 = Android 6.0，如乐视 1 Pro）。
 *
 * Direct Boot / DE 存储是 API 24+ 特性：Android 6.0 没有
 * createDeviceProtectedStorageContext()，直接调用会 NoSuchMethodError。
 * 降级策略：返回原上下文，配置/文件落到 CE 区（/data/data）。
 * 语义变化：锁屏未解锁期间服务读不到这些配置——但设备的开机解锁
 * 由 magisk 脚本先完成再拉服务，实际场景无影响。
 */
object Compat {
    /** API 24+ 返回设备保护(DE)存储上下文；API 23 及以下返回原上下文 */
    fun deContext(ctx: Context): Context =
        if (Build.VERSION.SDK_INT >= 24) ctx.createDeviceProtectedStorageContext() else ctx

    /** 启动前台服务：API 26+ 用 startForegroundService，低版本用 startService */
    fun startForegroundCompat(ctx: Context, intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
