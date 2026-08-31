package com.jarvis.mobile.control

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Bridges CommandDispatcher (which runs off a WebSocket callback, with no
 * Activity in hand) to the MediaProjection consent flow, which Android
 * requires be started from a foreground Activity.
 *
 * Flow:
 *   captureAndAwait() → broadcasts a request → MainActivity (already
 *   resumed, since the phone is being actively controlled) launches the
 *   system consent dialog → on approval, starts ScreenshotService with the
 *   granted projection token → service captures one frame → result flows
 *   back here via ScreenshotService.onCaptured.
 *
 * If MainActivity isn't in the foreground when a screenshot is requested,
 * this deliberately fails fast with a clear error rather than silently
 * capturing nothing — matching the "no hidden screen capture" requirement.
 */
class ScreenshotCapture(private val context: Context) {

    companion object {
        /** MainActivity sets this so requestConsent() can reach it directly
         * without a broadcast round-trip. Null when the app isn't foregrounded. */
        var activityBridge: ((onGranted: (Int, Intent) -> Unit, onDenied: () -> Unit) -> Unit)? = null
    }

    suspend fun captureAndAwait(): String? = suspendCancellableCoroutine { cont ->
        val bridge = activityBridge
        if (bridge == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        ScreenshotService.onCaptured = { base64 ->
            ScreenshotService.onCaptured = null
            if (cont.isActive) cont.resume(base64)
        }

        bridge(
            { resultCode, resultData ->
                ScreenshotService.pendingResultCode = resultCode
                ScreenshotService.pendingResultData = resultData
                val intent = Intent(context, ScreenshotService::class.java)
                context.startForegroundService(intent)
            },
            {
                ScreenshotService.onCaptured = null
                if (cont.isActive) cont.resume(null)
            },
        )
    }
}
