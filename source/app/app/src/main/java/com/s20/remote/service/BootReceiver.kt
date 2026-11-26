package com.s20.remote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启（Direct Boot 适配）：
 * - LOCKED_BOOT_COMPLETED：锁屏未解锁时即启动服务（DE 区可用）
 * - BOOT_COMPLETED：解锁后兜底（部分 ROM 不发 LOCKED 广播）
 * - USER_UNLOCKED：解锁后把历史 CE 区配置迁移到 DE 区
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.s20.remote.RETRY_START" -> {
                Log.i(TAG, "retry start service")
                startService(context)
                return
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "LOCKED_BOOT_COMPLETED: start service before unlock")
                startService(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "BOOT_COMPLETED: ensure service running")
                startService(context)
            }
            Intent.ACTION_USER_UNLOCKED -> {
                Log.i(TAG, "USER_UNLOCKED: migrate CE config to DE")
                try {
                    com.s20.remote.core.Config.migrateFromCredentialStorage()
                } catch (e: Throwable) {
                    Log.w(TAG, "migrate failed: ${e.message}")
                }
                startService(context)
            }
        }
    }

    private fun startService(context: Context) {
        try {
            val i = Intent(context, MainService::class.java)
            context.startForegroundService(i)
            Log.i(TAG, "MainService start requested")
        } catch (e: Throwable) {
            // Android 15 限制部分来源启动 dataSync 型前台服务：5 秒后经 AlarmManager 重试一次
            Log.e(TAG, "start service failed: ${e.message}, schedule retry")
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val pi = android.app.PendingIntent.getBroadcast(
                    context, 1001,
                    Intent(context, BootReceiver::class.java).setAction("com.s20.remote.RETRY_START"),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 5000, pi)
            } catch (e2: Throwable) {
                Log.e(TAG, "schedule retry failed: ${e2.message}")
            }
        }
    }
}
