package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.mobile.control.ScreenshotCapture
import com.jarvis.mobile.databinding.ActivityMainBinding
import com.jarvis.mobile.net.ControlConnectionService
import com.jarvis.mobile.pairing.PairingClient
import com.jarvis.mobile.pairing.PairingStore

/**
 * Pairing screen + connection status. Once paired, this Activity's main job
 * is to (a) let the user enable Accessibility, (b) let them disconnect, and
 * (c) act as the required foreground bridge for MediaProjection screenshot
 * consent (see ScreenshotCapture.activityBridge) — Android will not show
 * that system dialog without a resumed Activity to launch it from.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore

    private var pendingProjectionGranted: ((Int, Intent) -> Unit)? = null
    private var pendingProjectionDenied: (() -> Unit)? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK && activityResult.data != null) {
            pendingProjectionGranted?.invoke(activityResult.resultCode, activityResult.data!!)
        } else {
            pendingProjectionDenied?.invoke()
        }
        pendingProjectionGranted = null
        pendingProjectionDenied = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pairingStore = PairingStore(this)

        binding.pairButton.setOnClickListener { attemptPair() }
        binding.forgetButton.setOnClickListener { forgetDevice() }
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        // Wire this Activity as the live bridge for screenshot consent while
        // it's in the foreground; cleared in onPause so ScreenshotCapture
        // fails fast instead of hanging if the app isn't visible.
        ScreenshotCapture.activityBridge = { onGranted, onDenied ->
            requestProjectionConsent(onGranted, onDenied)
        }
        renderState()
    }

    override fun onPause() {
        super.onPause()
        ScreenshotCapture.activityBridge = null
    }

    private fun renderState() {
        val pairing = pairingStore.load()
        if (pairing == null) {
            binding.pairingGroup.visibility = android.view.View.VISIBLE
            binding.connectedGroup.visibility = android.view.View.GONE
            binding.statusText.text = getString(R.string.status_disconnected)
        } else {
            binding.pairingGroup.visibility = android.view.View.GONE
            binding.connectedGroup.visibility = android.view.View.VISIBLE
            binding.statusText.text = getString(R.string.status_connected)
            binding.deviceNameText.text = "Paired with ${pairing.pcName}"
            binding.accessibilityButton.text = if (isAccessibilityServiceEnabled())
                "Accessibility enabled ✓" else getString(R.string.enable_accessibility_prompt)

            // Start (or restart) the connection service now that we know we're paired.
            startForegroundService(Intent(this, ControlConnectionService::class.java))
        }
    }

    private fun attemptPair() {
        val host = binding.hostInput.text.toString().trim()
        val code = binding.codeInput.text.toString().trim()
        binding.pairingError.visibility = android.view.View.GONE

        if (host.isBlank() || code.length != 6) {
            showPairingError("Enter the PC address and the 6-digit code.")
            return
        }

        binding.pairButton.isEnabled = false
        Thread {
            val result = PairingClient().pair(
                hostAndPort = host,
                useTls = host.startsWith("https://") || host.contains(":443"),
                code = code,
                deviceName = Build.MODEL ?: "Android Device",
            )
            runOnUiThread {
                binding.pairButton.isEnabled = true
                when (result) {
                    is PairingClient.Result.Success -> {
                        pairingStore.save(
                            PairingStore.Pairing(
                                host = host.removePrefix("https://").removePrefix("http://"),
                                useTls = false,
                                deviceId = result.deviceId,
                                deviceToken = result.deviceToken,
                                pcName = "JARVIS PC",
                            )
                        )
                        renderState()
                    }
                    is PairingClient.Result.Failure -> showPairingError(result.message)
                }
            }
        }.start()
    }

    private fun forgetDevice() {
        stopService(Intent(this, ControlConnectionService::class.java))
        pairingStore.clear()
        renderState()
    }

    private fun showPairingError(message: String) {
        binding.pairingError.text = message
        binding.pairingError.visibility = android.view.View.VISIBLE
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${com.jarvis.mobile.control.JarvisAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun requestProjectionConsent(onGranted: (Int, Intent) -> Unit, onDenied: () -> Unit) {
        pendingProjectionGranted = onGranted
        pendingProjectionDenied = onDenied
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
