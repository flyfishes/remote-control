package com.s20.remote.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.graphics.ImageFormat
import android.media.MediaRecorder
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.s20.remote.core.Capabilities
import com.s20.remote.core.CommandRouter
import com.s20.remote.core.Logger
import com.s20.remote.core.RootShell
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 媒体模块：拍照（media.photo）与录音（media.record）。
 *
 * 拍照：Camera2 直接拍一帧 JPEG（无需人工操作，屏幕可熄灭）。
 *   - 权限保障：缺 CAMERA 时 root 下自动 `pm grant` + `appops set ... CAMERA allow`
 *     （Android 11+ 后台访问相机需 appop 放行，普通 FGS 不满足 while-in-use 条件）；
 *   - 仍不可用时降级：root `am start` 拉起系统相机 App 由人工拍摄，返回 mode=cameraApp；
 *   - 前/后摄由 facing 参数选择。
 * 录音：MediaRecorder AAC/m4a，5~10 秒（同步回传受中转 15s 命令超时约束）。
 * 本地留存：默认另存 /sdcard/DCIM|Music/S20Remote（root cp，App 私有缓存留临时文件）。
 */
object MediaModule {
    private const val TAG = "Media"

    private val photoBusy = AtomicBoolean(false)
    private val recBusy = AtomicBoolean(false)

    // 异步录音状态（进程级，长录音可达 24 小时，命令须立即返回）
    @Volatile private var recThread: Thread? = null
    @Volatile private var recStartedAt = 0L          // System.currentTimeMillis
    @Volatile private var recTargetSec = 0L          // 目标时长（秒）
    @Volatile private var recStopRequested = false   // 手动停止标志
    @Volatile private var recTmpPath = ""            // 录制中的临时文件
    @Volatile private var recLastResult: JSONObject? = null  // 最近一次录音完成结果

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        CommandRouter.registerAsync("media.photo") { photo(app, it) }
        CommandRouter.registerAsync("media.record") { record(app, it) }
        CommandRouter.registerAsync("media.recordStop") { recordStop() }
        CommandRouter.register("media.recordStatus") { recordStatus() }
    }

    // ---------- 权限保障 ----------

    /** 缺运行时权限时 root 下自动授予；成功返回 null，失败返回 NEED_PERM */
    private fun ensurePerm(perm: String, appop: String, feature: String): JSONObject? {
        if (Capabilities.hasPerm(perm)) return null
        if (Capabilities.root()) {
            val pkg = Capabilities.ctx().packageName
            RootShell.exec("pm grant $pkg $perm", 10_000)
            // Android 11+ 后台使用相机/麦克风需 appop 放行（FGS 非 while-in-use 场景）
            RootShell.exec("appops set $pkg $appop allow", 10_000)
            if (Capabilities.hasPerm(perm)) {
                Logger.i(TAG, "$perm granted via pm grant + appops")
                return null
            }
        }
        return Capabilities.needPerm(
            "缺少 ${perm.substringAfterLast('.')} 权限" +
                    (if (Capabilities.root()) "（自动授权未生效，请在系统设置中手动允许）" else "，且无 root 无法自动授权；打开本 App 前台时会弹窗申请"),
            feature
        )
    }

    // ---------- 本地保存 ----------

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 保存到 /sdcard；root 优先，其次直接写。返回实际路径，失败返回 "" */
    private fun saveToSdcard(src: File, dir: String, name: String): String {
        val dst = "$dir/$name"
        val mk = "mkdir -p '$dir'"
        if (Capabilities.root()) {
            val r = RootShell.exec("$mk && cp '${src.absolutePath}' '$dst' && chmod 644 '$dst'", 15_000)
            if (r.ok) return dst
        }
        return try {
            val d = File(dir)
            if (d.isDirectory || d.mkdirs()) {
                src.copyTo(File(dst), overwrite = true)
                dst
            } else ""
        } catch (e: Exception) {
            Logger.w(TAG, "save $dst: ${e.message}"); ""
        }
    }

    // ---------- 拍照 ----------

    /**
     * 拍照/录音前的前置准备：解锁屏幕 + 把 App 置于前台。
     * Samsung 把 RECORD_AUDIO/CAMERA 的 appops 钉死为 foreground（仅"使用中"允许），
     * 后台执行会被系统静音/拒绝；App 前台后即可正常采集。
     * @return null=准备完成；非 null=错误响应
     */
    private fun ensureForegroundForCapture(): JSONObject? {
        if (!RootShell.available()) return null   // 无 root：不做前置，交由系统权限判定
        try {
            if (!StatusModule.ensureUnlocked())
                return JSONObject().put("ok", false)
                    .put("error", "屏幕锁定且自动解锁失败（未开启自动解锁或 PIN 不正确），请先解锁设备再试")
            RootShell.exec("am start -n com.s20.remote/.MainActivity", 10_000)
            Thread.sleep(1200)   // 等 Activity resume，进入"使用中"判定
        } catch (e: Exception) {
            Logger.w(TAG, "ensureForegroundForCapture: ${e.message}")
        }
        return null
    }

    private fun photo(ctx: Context, p: JSONObject): JSONObject {
        if (!photoBusy.compareAndSet(false, true))
            return JSONObject().put("ok", false).put("error", "上一次拍照仍在进行中，请稍后重试")
        try {
            val facing = p.optString("facing", "back")
            val max = p.optInt("max", 1080).coerceIn(360, 4096)
            val quality = p.optInt("quality", 80).coerceIn(30, 95)
            val save = p.optBoolean("save", true)
            val t0 = System.currentTimeMillis()

            ensureForegroundForCapture()?.let { return it }

            ensurePerm(android.Manifest.permission.CAMERA, "CAMERA", "media.photo")?.let { denied ->
                // 降级：root 下拉起系统相机 App 人工拍摄（照片在 DCIM，可用文件模块取回）
                if (Capabilities.root()) {
                    RootShell.exec("am start -a android.media.action.STILL_IMAGE_CAMERA", 15_000)
                    return JSONObject().put("ok", true).put("mode", "cameraApp")
                        .put("hint", "已拉起系统相机，请在手机上手动拍摄；照片保存于 DCIM，可通过「文件」模块取回")
                }
                return denied
            }

            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val lens = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                try {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lens
                } catch (e: Exception) { false }
            } ?: return Capabilities.notSupported("未找到${if (facing == "front") "前" else "后"}置摄像头", "media.photo")

            val chars = cm.getCameraCharacteristics(cameraId)
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val best = sizes?.maxByOrNull { s -> s.width.toLong() * s.height }
                ?: return Capabilities.notSupported("摄像头不支持 JPEG 输出", "media.photo")

            val latch = CountDownLatch(1)
            var jpeg: ByteArray? = null
            var err: String? = null
            val thread = HandlerThread("media-photo").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(best.width, best.height, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        img.use { im ->
                            val buf = im.planes[0].buffer
                            val a = ByteArray(buf.remaining())
                            buf.get(a)
                            jpeg = a
                        }
                    }
                } catch (e: Exception) {
                    err = "读取图像失败: ${e.message}"
                } finally {
                    latch.countDown()
                }
            }, handler)

            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            try {
                cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        try {
                            d.createCaptureSession(listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        session = s
                                        try {
                                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(reader.surface)
                                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            }
                                            s.capture(req.build(), null, handler)
                                        } catch (e: Exception) {
                                            err = "提交拍摄请求失败: ${e.message}"
                                            latch.countDown()
                                        }
                                    }

                                    override fun onConfigureFailed(s: CameraCaptureSession) {
                                        err = "相机会话配置失败"
                                        latch.countDown()
                                    }
                                }, handler)
                        } catch (e: Exception) {
                            err = "创建会话失败: ${e.message}"
                            latch.countDown()
                        }
                    }

                    override fun onDisconnected(d: CameraDevice) { latch.countDown() }
                    override fun onError(d: CameraDevice, error: Int) {
                        err = "相机打开失败 code=$error"
                        latch.countDown()
                    }
                }, handler)

                if (!latch.await(12, TimeUnit.SECONDS) && err == null) err = "拍照超时（12s）"
            } finally {
                try { session?.close() } catch (_: Exception) {}
                try { device?.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                thread.quitSafely()
            }

            val raw = jpeg ?: return JSONObject().put("ok", false)
                .put("error", err ?: "未获得图像数据")

            // 解码 + 等比缩放 + JPEG 压缩（同截图模块模式，控制传输体积与内存）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0) return JSONObject().put("ok", false).put("error", "图像解码失败")
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= max) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return JSONObject().put("ok", false).put("error", "图像解码失败")
            val scaled: Bitmap = if (bmp.width > max) {
                Bitmap.createScaledBitmap(bmp, max, (bmp.height.toLong() * max / bmp.width).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream(256 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val bytes = bos.toByteArray()
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()

            // 临时文件（DE 缓存，锁屏可用）+ /sdcard 留存
            val dir = File(ctx.createDeviceProtectedStorageContext().cacheDir, "media").apply { mkdirs() }
            val tmp = File(dir, "photo.jpg")
            tmp.writeBytes(raw)
            val name = "photo_${stamp()}.jpg"
            val saved = if (save) saveToSdcard(tmp, "/sdcard/DCIM/S20Remote", name) else ""
            try { tmp.delete() } catch (_: Exception) {}

            val ms = System.currentTimeMillis() - t0
            Logger.i(TAG, "photo $facing ${bounds.outWidth}x${bounds.outHeight} -> ${scaled.width}x${scaled.height} ${bytes.size}B ${ms}ms cam=$cameraId")
            return JSONObject()
                .put("ok", true).put("mode", "camera2")
                .put("ts", System.currentTimeMillis())
                .put("facing", facing).put("cameraId", cameraId)
                .put("width", bounds.outWidth).put("height", bounds.outHeight)
                .put("outWidth", scaled.width).put("outHeight", scaled.height)
                .put("bytes", bytes.size).put("ms", ms)
                .put("mime", "image/jpeg")
                .put("savedPath", saved)
                .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Exception) {
            Logger.w(TAG, "photo: ${e.message}")
            return JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        } finally {
            photoBusy.set(false)
        }
    }

    // ---------- 录音（异步：命令立即返回，后台录制，最长 24 小时） ----------

    /**
     * 启动录音：立即返回，录制在独立线程进行。
     * seconds 默认 15，上限 86400（24 小时）。可用 media.recordStop 提前停止，
     * media.recordStatus 轮询进度，完成后文件在 /sdcard/Music/S20Remote。
     */
    private fun record(ctx: Context, p: JSONObject): JSONObject {
        if (recBusy.get())
            return JSONObject().put("ok", false).put("recording", true)
                .put("error", "录音正在进行中，可用 media.recordStop 停止或 media.recordStatus 查询进度")

        val seconds = p.optLong("seconds", 15L).coerceIn(1L, 86_400L)

        ensurePerm(android.Manifest.permission.RECORD_AUDIO, "MICROPHONE", "media.record")?.let { return it }
        ensureForegroundForCapture()?.let { return it }

        val dir = File(ctx.createDeviceProtectedStorageContext().cacheDir, "media").apply { mkdirs() }
        val tmp = File(dir, "record.m4a")
        try { tmp.delete() } catch (_: Exception) {}

        val mr = MediaRecorder()
        try {
            // 音源选 CAMCORDER 而非 MIC：Samsung 设备会把 RECORD_AUDIO op 的
            // uid 模式钉死为 foreground（仅"使用中"允许），后台服务用 MIC 源
            // 会被 AudioService 静音（dumpsys 显示 rec ... silenced）。
            mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(48_000)
            mr.setAudioSamplingRate(22_050)
            mr.setAudioChannels(1)
            mr.setOutputFile(tmp.absolutePath)
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            try { mr.release() } catch (_: Exception) {}
            return JSONObject().put("ok", false)
                .put("error", "录音启动失败: ${e.message}（可能正在通话或被其他应用占用麦克风）")
        }

        recBusy.set(true)
        recStopRequested = false
        recStartedAt = System.currentTimeMillis()
        recTargetSec = seconds
        recTmpPath = tmp.absolutePath
        recLastResult = null

        recThread = thread(name = "media-record") {
            var stopErr: String? = null
            try {
                // 目标时长到达或收到停止标志即结束
                val deadline = recStartedAt + seconds * 1000
                while (System.currentTimeMillis() < deadline && !recStopRequested) {
                    Thread.sleep(250)
                }
                try { mr.stop() } catch (e: Exception) { stopErr = e.message }
                try { mr.release() } catch (_: Exception) {}
            } catch (_: InterruptedException) {
                try { mr.stop() } catch (_: Exception) {}
                try { mr.release() } catch (_: Exception) {}
            }

            val ms = System.currentTimeMillis() - recStartedAt
            val ok = tmp.exists() && tmp.length() >= 512L
            var result = JSONObject()
            if (ok) {
                val size = tmp.length()
                val name = "record_${stamp()}.m4a"
                val saved = saveToSdcard(tmp, "/sdcard/Music/S20Remote", name)
                try { tmp.delete() } catch (_: Exception) {}
                Logger.i(TAG, "record done ${ms / 1000}s ${size}B stop=$stopErr")
                result = JSONObject()
                    .put("ok", true)
                    .put("ts", System.currentTimeMillis())
                    .put("seconds", ms / 1000).put("bytes", size).put("ms", ms)
                    .put("mime", "audio/mp4")
                    .put("savedPath", saved)
                    .put("stopped", recStopRequested)
                if (saved.isNotEmpty()) result.put("path", saved)
            } else {
                result = JSONObject().put("ok", false)
                    .put("error", "录音数据无效: ${stopErr ?: "文件过短"}")
            }
            recLastResult = result
            recTmpPath = ""
            recBusy.set(false)
        }

        return JSONObject()
            .put("ok", true).put("recording", true)
            .put("seconds", seconds)
            .put("hint", "录音已开始，${seconds} 秒后自动结束；可用 media.recordStop 提前停止，media.recordStatus 查询进度。完成后文件保存于 /sdcard/Music/S20Remote")
    }

    /** 提前停止录音（阻塞等待收尾，最多 5 秒） */
    private fun recordStop(): JSONObject {
        if (!recBusy.get()) {
            recLastResult?.let {
                return JSONObject().put("ok", true).put("recording", false).put("last", it)
            }
            return JSONObject().put("ok", false).put("recording", false)
                .put("error", "当前没有正在进行的录音")
        }
        recStopRequested = true
        val t0 = System.currentTimeMillis()
        while (recBusy.get() && System.currentTimeMillis() - t0 < 5000) {
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        return recLastResult?.let {
            JSONObject().put("ok", true).put("recording", false).put("last", it)
        } ?: JSONObject().put("ok", true).put("recording", recBusy.get())
            .put("hint", "停止指令已下发，录音正在收尾")
    }

    /** 录音状态：进度（录制中）或最近一次结果 */
    private fun recordStatus(): JSONObject {
        if (recBusy.get()) {
            val elapsed = (System.currentTimeMillis() - recStartedAt) / 1000
            val tmp = File(recTmpPath)
            return JSONObject()
                .put("ok", true).put("recording", true)
                .put("elapsedSec", elapsed).put("targetSec", recTargetSec)
                .put("bytes", if (tmp.exists()) tmp.length() else 0L)
        }
        recLastResult?.let {
            return JSONObject().put("ok", true).put("recording", false).put("last", it)
        }
        return JSONObject().put("ok", true).put("recording", false)
    }
}
