package com.jarvis.mobile.net

import android.content.Context
import com.jarvis.mobile.control.AppLauncher
import com.jarvis.mobile.control.JarvisAccessibilityService
import com.jarvis.mobile.control.SystemControl
import com.jarvis.mobile.control.ScreenshotCapture
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Single point that turns a protocol command ({"type": "...", "payload": {...}})
 * into an action, and turns the outcome into a ControlConnection.CommandResult.
 *
 * Every branch here is defensive: unsupported command → clear error, missing
 * Accessibility permission → clear error naming what's needed, never a raw
 * exception or a silent "Done." This is the "Graceful Failure" requirement
 * from the spec made concrete.
 */
class CommandDispatcher(private val context: Context) {

    private val appLauncher    = AppLauncher(context)
    private val systemControl  = SystemControl(context)
    private val screenshotCapture = ScreenshotCapture(context)

    suspend fun dispatch(type: String, payload: JSONObject): ControlConnection.CommandResult {
        return when (type) {

            "open_app" -> {
                val pkg = payload.optString("package").ifBlank { payload.optString("app_name") }
                if (pkg.isBlank()) return fail("No app specified.")
                when (val r = appLauncher.open(pkg)) {
                    is AppLauncher.Result.Opened   -> ok("${r.label} opened.")
                    AppLauncher.Result.NotFound    -> fail("Application not found.")
                }
            }

            "screenshot" -> {
                val base64 = screenshotCapture.captureAndAwait()
                if (base64 != null) ok(base64) else fail(
                    "Could not capture the screen. Screen recording permission may have been denied."
                )
            }

            "tap" -> requireAccessibility { service ->
                val x = payload.optInt("x", -1)
                val y = payload.optInt("y", -1)
                if (x < 0 || y < 0) return@requireAccessibility fail("Invalid coordinates.")
                val done = suspendCancellableCoroutine<Boolean> { cont ->
                    service.dispatchTap(x, y) { cont.resume(it) }
                }
                if (done) ok("Tapped.") else fail("The tap gesture was cancelled by the system.")
            }

            "swipe" -> requireAccessibility { service ->
                val x1 = payload.optInt("x1", -1); val y1 = payload.optInt("y1", -1)
                val x2 = payload.optInt("x2", -1); val y2 = payload.optInt("y2", -1)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return@requireAccessibility fail("Invalid coordinates.")
                val duration = payload.optLong("duration_ms", 300L)
                val done = suspendCancellableCoroutine<Boolean> { cont ->
                    service.dispatchSwipe(x1, y1, x2, y2, duration) { cont.resume(it) }
                }
                if (done) ok("Swiped.") else fail("The swipe gesture was cancelled by the system.")
            }

            "type_text" -> requireAccessibility { service ->
                val text = payload.optString("text")
                if (text.isBlank()) return@requireAccessibility fail("No text provided.")
                // Never log `text` — logging rules in the spec apply here too.
                if (service.typeIntoFocusedField(text)) ok("Typed.")
                else fail("No text field is focused on screen right now.")
            }

            "find_element" -> requireAccessibility { service ->
                val query = payload.optString("query")
                if (query.isBlank()) return@requireAccessibility fail("No element description provided.")
                val center = service.findNodeCenterByText(query)
                if (center != null) {
                    ok(JSONObject().put("x", center.first).put("y", center.second).toString())
                } else fail("Could not find an on-screen element matching '$query'.")
            }

            "back"          -> requireAccessibility { s -> globalActionResult(s.pressBack(), "back") }
            "home"          -> requireAccessibility { s -> globalActionResult(s.pressHome(), "home") }
            "recent_apps"   -> requireAccessibility { s -> globalActionResult(s.pressRecentApps(), "recent apps") }
            "lock_screen"   -> requireAccessibility { s ->
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                    fail("Locking the screen remotely requires Android 9 or newer.")
                } else globalActionResult(s.pressLockScreen(), "lock screen")
            }

            "volume_up"     -> { systemControl.volumeUp();   ok("Volume up.") }
            "volume_down"   -> { systemControl.volumeDown(); ok("Volume down.") }
            "volume_mute"   -> { systemControl.volumeMute(); ok("Muted.") }

            "media_play"     -> { systemControl.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY);  ok("Playing.") }
            "media_pause"    -> { systemControl.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); ok("Paused.") }
            "media_next"     -> { systemControl.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);  ok("Skipped.") }
            "media_previous" -> { systemControl.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); ok("Went to previous track.") }

            "device_info" -> ok(
                JSONObject()
                    .put("model", systemControl.deviceModel())
                    .put("android_version", systemControl.androidVersion())
                    .put("battery", systemControl.batteryPercent() ?: JSONObject.NULL)
                    .toString()
            )

            else -> fail("Unsupported command: $type")
        }
    }

    private suspend fun requireAccessibility(
        block: suspend (JarvisAccessibilityService) -> ControlConnection.CommandResult,
    ): ControlConnection.CommandResult {
        val service = JarvisAccessibilityService.instance
            ?: return fail("This requires Accessibility permission, which isn't enabled yet. Enable it in JARVIS Mobile settings.")
        return block(service)
    }

    private fun globalActionResult(success: Boolean, label: String) =
        if (success) ok("Pressed $label.") else fail("Could not perform '$label'.")

    private fun ok(result: String) = ControlConnection.CommandResult(success = true, result = result)
    private fun fail(error: String) = ControlConnection.CommandResult(success = false, error = error)
}
