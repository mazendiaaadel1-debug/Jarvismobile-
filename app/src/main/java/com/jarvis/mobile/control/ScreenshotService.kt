package com.jarvis.mobile.control

import android.app.*
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Screenshot capture via the official MediaProjection API — the only
 * sanctioned way for an app to capture the screen on modern Android without
 * root. Android REQUIRES a fresh user consent dialog (system-drawn, cannot
 * be suppressed or pre-approved by this app) each time a MediaProjection
 * session starts; this is Android's own security boundary, not a
 * limitation of this implementation, and it means "silent screenshot" is
 * not something this app is capable of or attempting.
 *
 * Runs as a short-lived foreground service (required by
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION since Android 14) for the
 * duration of exactly one capture, then stops itself.
 */
class ScreenshotService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "jarvis_screenshot"
        private const val NOTIF_ID = 42

        /** Set by MainActivity right after the user approves the system
         * projection-consent dialog; consumed once by this service. */
        var pendingResultCode: Int = 0
        var pendingResultData: Intent? = null

        /** Result callback wired by CommandDispatcher — Base64 PNG or null on failure. */
        var onCaptured: ((String?) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = pendingResultCode
        val resultData = pendingResultData
        pendingResultData = null

        if (resultCode == 0 || resultData == null) {
            onCaptured?.invoke(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)

        captureOneFrame(projection) { base64Png ->
            onCaptured?.invoke(base64Png)
            projection.stop()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun captureOneFrame(projection: MediaProjection, onDone: (String?) -> Unit) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.PNG, 90, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                onDone(base64)
            } catch (e: Exception) {
                onDone(null)
            } finally {
                image.close()
                virtualDisplay?.release()
                imageReader.close()
            }
        }, null)

        virtualDisplay = projection.createVirtualDisplay(
            "jarvis-screenshot",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null,
        )
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screenshot capture", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Mobile")
            .setContentText("Taking a screenshot for your PC…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
