package com.choury.bridgemcp.caps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.BridgeService
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// screen.capture：经 MediaProjection 虚屏抓单帧存盘，返回路径供 agent 用 bridge.pull 拉取
// （bridge 动态工具只回传 JSON 结果，图片内容必须走路径搬运）。
// 每次投屏会话都需用户在 App 内点一次系统授权弹窗，授权在服务存活期内有效。
object ScreenCapability {

    private const val SCHEMA = """{
        "type": "object",
        "properties": {
            "format": {"type": "string", "enum": ["png", "jpeg"], "default": "png", "description": "image format of the saved file"}
        }
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        add(
            Capability(
                name = "screen.capture",
                description = "Take one screenshot of this phone's screen and save it to a file. Returns path, size, width, height — pull the file with bridge.pull to view it. Requires screen-capture authorization in the app.",
                inputSchema = SCHEMA,
            ),
            { args, _ -> capture(args) },
        )
    }

    @SuppressLint("WrongConstant")
    private fun capture(args: JSONObject): CapResult {
        val ctx = BridgeApp.instance
        val projection = BridgeService.projection
            ?: throw IOException("screen capture not authorized; tap the projection authorize button in the app first")
        val format = args.optString("format", "png").lowercase()
        if (format != "png" && format != "jpeg") throw IOException("format must be png or jpeg")

        val metrics = screenMetrics(ctx)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val handler = Handler(Looper.getMainLooper())

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        try {
            val latch = CountDownLatch(1)
            var frame: Image? = null
            reader.setOnImageAvailableListener({ r ->
                if (frame == null) {
                    frame = r.acquireLatestImage()
                    if (frame != null) latch.countDown()
                }
            }, handler)
            display = projection.createVirtualDisplay(
                "bridge-screen-capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
            )
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw IOException("no frame within 10s")
            }
            val image = frame ?: throw IOException("no frame captured")

            val file = File(ctx.getExternalFilesDir(null), "screenshot-${timestamp()}.$format")
            val bitmap = bitmapFrom(image, width, height)
            image.close()
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(
                        if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        90, out
                    )
                ) throw IOException("bitmap compress failed")
            }
            bitmap.recycle()
            return CapResult.Json(
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("size", file.length())
                    .put("width", width)
                    .put("height", height)
            )
        } finally {
            display?.release()
            reader.close()
        }
    }

    private fun screenMetrics(ctx: Context): DisplayMetrics {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = ctx.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun bitmapFrom(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
