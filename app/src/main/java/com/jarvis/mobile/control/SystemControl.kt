package com.jarvis.mobile.control

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.content.Intent
import android.content.IntentFilter

/**
 * Wraps the official Android APIs for volume and device info — none of
 * this needs the Accessibility Service, so it keeps working even before
 * the user grants that permission (per spec: "الأوامر العادية... يمكن
 * تنفيذها مباشرة").
 */
class SystemControl(private val context: Context) {

    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    fun volumeMute() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    /** Media transport (play/pause/next/previous) via the standard media-key
     * broadcast — works with whatever app currently holds media focus,
     * matching how a physical headset button behaves. */
    fun mediaKey(keyCode: Int) {
        val eventTimeDown = android.os.SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(eventTimeDown, eventTimeDown, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(eventTimeDown, eventTimeDown, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    fun batteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    fun androidVersion(): String = Build.VERSION.RELEASE ?: "unknown"
}
