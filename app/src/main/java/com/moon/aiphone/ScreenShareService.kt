package com.moon.aiphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * 屏幕共享前台服务（手机本地，无需任何服务器）。
 * 持有 MediaProjection 会话，按需抓取最近一帧屏幕画面，
 * 由 ChatActivity 定时取帧后直接通过用户配置的模型 API 识图。
 *
 * Android 14+ 要求 MediaProjection 必须运行在 mediaProjection 类型的前台服务中，
 * 且 getMediaProjection 要在 startForeground 之后调用。
 */
class ScreenShareService : Service() {

    companion object {
        const val ACTION_START = "screen_share_start"
        const val ACTION_STOP = "screen_share_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AI_ID = "ai_id"
        const val EXTRA_AI_NAME = "ai_name"
        const val BROADCAST_STOPPED = "com.moon.aiphone.SCREEN_SHARE_STOPPED"

        @Volatile var isRunning = false
            private set
        @Volatile var sharingAiId: String = ""
            private set
        @Volatile private var instance: ScreenShareService? = null

        /** 抓取最近一帧：返回 (JPEG base64 data URI, 感知哈希)，失败返回 null */
        fun captureFrame(): Pair<String, Long>? = instance?.captureLatestFrame()

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ScreenShareService::class.java).apply {
                    putExtra("action", ACTION_STOP)
                })
            } catch (_: Exception) {}
        }
    }

    private val TAG = "ScreenShare"
    private val CHANNEL_ID = "screen_share_channel"
    private val NOTIFY_ID = 7301

    private val projectionLock = Object()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("action")) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val aiName = intent.getStringExtra(EXTRA_AI_NAME) ?: ""
                sharingAiId = intent.getStringExtra(EXTRA_AI_ID) ?: ""
                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                startAsForeground(aiName)
                startProjection(resultCode, resultData)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(aiName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "屏幕共享", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, ScreenShareService::class.java).apply { putExtra("action", ACTION_STOP) }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("正在共享屏幕")
            .setContentText(if (aiName.isNotEmpty()) "$aiName 正在看你的屏幕" else "屏幕画面对角色可见")
            .setOngoing(true)
            .addAction(0, "停止共享", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        synchronized(projectionLock) {
            releaseProjectionLocked()
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                val projection = mpm?.getMediaProjection(resultCode, resultData)
                if (projection == null) {
                    Log.w(TAG, "MediaProjection unavailable")
                    stopSelf(); return
                }
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 系统或用户从状态栏结束了投屏
                        stopSelf()
                    }
                }, null)

                val dm = resources.displayMetrics
                // 预缩放：长边不超过 1080，省内存也省后续压缩时间
                val scale = minOf(1f, 1080f / maxOf(dm.widthPixels, dm.heightPixels))
                val capW = maxOf(1, (dm.widthPixels * scale).toInt())
                val capH = maxOf(1, (dm.heightPixels * scale).toInt())

                imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "MjiScreenShare", capW, capH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface, null, null
                )
                mediaProjection = projection
                isRunning = true
                instance = this
                Log.i(TAG, "screen share projection ready ${capW}x${capH}")
            } catch (e: Exception) {
                Log.e(TAG, "projection start failed: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun captureLatestFrame(): Pair<String, Long>? {
        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            var image = synchronized(projectionLock) { imageReader?.acquireLatestImage() }
            if (image == null) {
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                image = synchronized(projectionLock) { imageReader?.acquireLatestImage() }
            }
            if (image == null) return null

            image.use { img ->
                val width = img.width
                val height = img.height
                val plane = img.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride

                bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                bitmap!!.copyPixelsFromBuffer(plane.buffer)
                cropped = if (paddedWidth != width) Bitmap.createBitmap(bitmap!!, 0, 0, width, height) else bitmap
            }

            val hash = averageHash(cropped!!)
            val out = ByteArrayOutputStream()
            cropped!!.compress(Bitmap.CompressFormat.JPEG, 75, out)
            val b64 = "data:image/jpeg;base64," +
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return Pair(b64, hash)
        } catch (e: Exception) {
            Log.e(TAG, "capture failed: ${e.message}")
            return null
        } finally {
            if (cropped != null && cropped != bitmap) cropped?.recycle()
            bitmap?.recycle()
        }
    }

    /** 8x8 灰度均值哈希，用于判断两帧画面是否基本相同（省掉重复识图） */
    private fun averageHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 8, 8, true)
        val gray = IntArray(64)
        var total = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = small.getPixel(x, y)
            val g = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            gray[y * 8 + x] = g; total += g
        }
        small.recycle()
        val avg = total / 64
        var hash = 0L
        for (i in 0 until 64) if (gray[i] > avg) hash = hash or (1L shl i)
        return hash
    }

    private fun releaseProjectionLocked() {
        isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    override fun onDestroy() {
        synchronized(projectionLock) { releaseProjectionLocked() }
        if (instance == this) instance = null
        sharingAiId = ""
        try { sendBroadcast(Intent(BROADCAST_STOPPED).setPackage(packageName)) } catch (_: Exception) {}
        super.onDestroy()
    }
}
