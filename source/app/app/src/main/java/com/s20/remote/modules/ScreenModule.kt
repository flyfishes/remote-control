package com.s20.remote.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 屏幕截图（远程查看当前屏幕）。
 *
 * 实现：root 执行 `screencap -p` 抓到设备保护(DE)缓存目录（锁屏未解锁也能读写），
 * 再用 BitmapFactory 解码 → 等比缩到 max 宽 → JPEG 压缩 → base64 回传。
 * 直接回传原始 PNG 会有 1~3MB，经中转 WebSocket 传输慢且会撑大事件库，
 * 因此统一压缩成 JPEG 缩略图（默认宽 720、质量 70，约 80~200KB）。
 *
 * 注意：屏幕处于 Asleep/Dozing 时 screencap 通常得到全黑图，可在状态页先「唤醒」。
 */
object ScreenModule {
    private const val TAG = "Screen"
    private val lock = Any()

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        CommandRouter.register("screen.capture") { p -> capture(app, p) }
    }

    /** 截图原始 PNG 的落盘目录（DE 区，锁屏可用；API 23 降级 CE） */
    private fun shotDir(ctx: Context): File =
        File(com.s20.remote.core.Compat.deContext(ctx).cacheDir, "shot").apply { mkdirs() }

    private fun capture(ctx: Context, p: JSONObject): JSONObject = synchronized(lock) {
        val maxW = p.optInt("max", 720).coerceIn(240, 1440)
        val quality = p.optInt("quality", 70).coerceIn(30, 95)
        val t0 = System.currentTimeMillis()
        // 截图依赖 root 执行 screencap（MediaProjection 方案需每次人工确认，服务端无人值守不可用）
        if (!com.s20.remote.core.RootShell.available()) {
            return@synchronized com.s20.remote.core.Capabilities.needRoot("screen.capture")
        }
        try {
            val dir = shotDir(ctx)
            val raw = File(dir, "shot.png")
            try { raw.delete() } catch (_: Exception) { }
            // 预创建空文件：文件先由本进程建立（SELinux label 带 app 自身的 category），
            // root 覆盖写不会改变 inode label。否则（如 Android 6 乐视）root 创建的文件
            // label 是 app_data_file:s0 无 category，本进程读取会被 MLS 规则拒绝导致解码失败。
            try { raw.createNewFile() } catch (_: Exception) { }
            val q = "'" + raw.absolutePath + "'"

            // 1) 常规方式；2) 指定 display 0；3) 老版本只支持 stdout 重定向
            var cmdUsed = "screencap -p $q"
            RootShell.exec(cmdUsed, 20_000)
            if (!raw.exists() || raw.length() < 1024L) {
                cmdUsed = "screencap -p -d 0 $q"
                RootShell.exec(cmdUsed, 20_000)
            }
            if (!raw.exists() || raw.length() < 1024L) {
                cmdUsed = "screencap -p > $q"
                RootShell.exec(cmdUsed, 20_000)
            }
            if (!raw.exists() || raw.length() < 1024L) {
                val w = StatusModule.wakefulness()
                Logger.w(TAG, "capture failed, wakefulness=$w")
                return@capture JSONObject().put("ok", false)
                    .put("wakefulness", w)
                    .put("error", "截图失败：screencap 未生成有效文件" +
                        (if (w.contains("Asleep") || w.contains("Dozing")) "（屏幕已熄灭，请先唤醒）" else ""))
            }
            // screencap 以 root 写入，改权限后本进程才能直接读
            RootShell.exec("chmod 644 $q", 5_000)

            val path = raw.absolutePath
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) {
                return@capture JSONObject().put("ok", false).put("error", "截图解码失败（文件可能不完整）")
            }
            var sample = 1
            while (srcW / (sample * 2) >= maxW) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@capture JSONObject().put("ok", false).put("error", "截图解码失败（Bitmap 为空）")
            val scaled: Bitmap = if (bmp.width > maxW) {
                Bitmap.createScaledBitmap(bmp, maxW, (bmp.height.toLong() * maxW / bmp.width).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream(256 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val bytes = bos.toByteArray()
            val outW = scaled.width
            val outH = scaled.height
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val wake = StatusModule.wakefulness()
            val ms = System.currentTimeMillis() - t0
            Logger.i(TAG, "capture ${srcW}x$srcH -> ${outW}x$outH ${bytes.size}B ${ms}ms via $cmdUsed")
            JSONObject()
                .put("ok", true)
                .put("ts", System.currentTimeMillis())
                .put("width", srcW).put("height", srcH)
                .put("outWidth", outW).put("outHeight", outH)
                .put("bytes", bytes.size).put("rawBytes", raw.length())
                .put("ms", ms).put("quality", quality)
                .put("mime", "image/jpeg")
                .put("wakefulness", wake)
                .put("path", path)
                .put("b64", b64)
        } catch (e: Exception) {
            Logger.w(TAG, "capture: ${e.message}")
            JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        }
    }
}
