package com.s20.remote.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备管理员：无 root 时提供 lockNow() 熄屏能力。
 * 激活方式：App 能力面板点「激活」（ACTION_ADD_DEVICE_ADMIN），用户确认一次即可。
 * 仅使用 force-lock 策略，不申请设备Owner/锁屏密码等强权限。
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        com.s20.remote.core.Logger.i("Admin", "device admin enabled (screen-off fallback)")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        com.s20.remote.core.Logger.i("Admin", "device admin disabled")
    }
}
