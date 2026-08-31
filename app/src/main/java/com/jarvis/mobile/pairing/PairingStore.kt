package com.jarvis.mobile.pairing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the paired PC's address and this device's long-lived device_token
 * using Android Keystore-backed encryption (never plaintext SharedPreferences —
 * matches the spec's "لا تخزن secrets بطريقة plaintext" requirement).
 *
 * One PC per install for v1: pairing again overwrites the previous pairing,
 * mirroring the "Forget Device" flow on the PC side, which only ever tracks
 * one device_token per paired phone anyway.
 */
class PairingStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_mobile_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    data class Pairing(
        val host: String,       // e.g. "192.168.1.20:8000"
        val useTls: Boolean,
        val deviceId: String,
        val deviceToken: String,
        val pcName: String,
    )

    fun save(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_HOST, pairing.host)
            .putBoolean(KEY_TLS, pairing.useTls)
            .putString(KEY_DEVICE_ID, pairing.deviceId)
            .putString(KEY_DEVICE_TOKEN, pairing.deviceToken)
            .putString(KEY_PC_NAME, pairing.pcName)
            .apply()
    }

    fun load(): Pairing? {
        val host  = prefs.getString(KEY_HOST, null) ?: return null
        val token = prefs.getString(KEY_DEVICE_TOKEN, null) ?: return null
        val id    = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        return Pairing(
            host        = host,
            useTls      = prefs.getBoolean(KEY_TLS, false),
            deviceId    = id,
            deviceToken = token,
            pcName      = prefs.getString(KEY_PC_NAME, "JARVIS PC") ?: "JARVIS PC",
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_HOST         = "host"
        private const val KEY_TLS          = "use_tls"
        private const val KEY_DEVICE_ID    = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PC_NAME      = "pc_name"
    }
}
