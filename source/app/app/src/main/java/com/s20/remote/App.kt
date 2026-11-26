package com.s20.remote

import android.app.Application
import android.content.Context
import com.s20.remote.core.Config
import com.s20.remote.core.Logger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        Config.init(this)
        com.s20.remote.core.Capabilities.init(this)
        Logger.i("App", "app created")
        // 开机脚本只拉起服务、不启动界面：此时 Activity 不会 resume，
        // 由进程级定时器接管「空闲 30s 熄屏」，避免解锁后屏幕常亮
        try {
            com.s20.remote.core.IdleScreenOff.armOnProcessStart()
        } catch (e: Exception) {
            Logger.w("App", "arm idle screen off failed: ${e.message}")
        }
    }

    companion object {
        /** 进程级 ApplicationContext（供无 Context 的静态工具使用） */
        lateinit var ctx: Context
            private set
    }
}
