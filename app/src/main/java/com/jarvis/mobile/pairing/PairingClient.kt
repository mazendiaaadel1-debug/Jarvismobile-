package com.jarvis.mobile.pairing

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One-shot HTTP call to the PC's /api/mobile/pair endpoint (see
 * dashboard/server.py). No auth token exists yet at this point — the
 * 6-digit code IS the credential, exactly like the existing browser-dashboard
 * QR/manual-key flow this reuses the pattern from.
 */
class PairingClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Success(
            val deviceId: String,
            val deviceToken: String,
        ) : Result()
        data class Failure(val message: String) : Result()
    }

    /** [hostAndPort] like "192.168.1.20:8000", no scheme. */
    fun pair(hostAndPort: String, useTls: Boolean, code: String, deviceName: String): Result {
        val scheme = if (useTls) "https" else "http"
        val url = "$scheme://$hostAndPort/api/mobile/pair"

        val body = JSONObject().apply {
            put("code", code.trim().uppercase())
            put("name", deviceName)
            put("model", Build.MODEL ?: "")
            put("android_version", Build.VERSION.RELEASE ?: "")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(body).build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    return Result.Failure(err?.takeIf { it.isNotBlank() } ?: "Pairing failed (${response.code}).")
                }
                val json = JSONObject(text)
                if (!json.optBoolean("ok", false)) {
                    return Result.Failure(json.optString("error", "Pairing failed."))
                }
                Result.Success(
                    deviceId    = json.getString("device_id"),
                    deviceToken = json.getString("device_token"),
                )
            }
        } catch (e: IOException) {
            Result.Failure("Could not reach $hostAndPort — check the address and that both devices are on the same Wi-Fi.")
        } catch (e: Exception) {
            Result.Failure("Pairing failed: ${e.message}")
        }
    }
}
