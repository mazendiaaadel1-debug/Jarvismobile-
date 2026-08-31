package com.jarvis.mobile.net

import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Owns the single persistent WebSocket to /ws/mobile-control on the paired
 * PC (see dashboard/server.py). Responsibilities matching the task spec:
 *   - connect / reconnect with backoff
 *   - heartbeat (battery level, keeps "last_seen" fresh on the PC side)
 *   - dispatch incoming {id, type, payload} commands to a handler
 *   - send back {id, type:"response", success, result|error}
 *   - graceful disconnect
 *
 * This class does NOT know how to execute any command — that's
 * CommandDispatcher's job. Keeping transport and command execution separate
 * means the Accessibility Service, MediaProjection, and system-API code paths
 * can each be tested without a live socket.
 */
class ControlConnection(
    private val context: Context,
    private val host: String,
    private val useTls: Boolean,
    private val deviceToken: String,
    private val scope: CoroutineScope,
    private val onCommand: suspend (id: String, type: String, payload: JSONObject) -> CommandResult,
    private val onStateChanged: (ConnectionState) -> Unit,
) {
    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

    data class CommandResult(val success: Boolean, val result: String? = null, val error: String? = null)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket: no read timeout, we manage liveness via heartbeat
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var manuallyClosed = false
    private var retryDelayMs = 1_000L
    private val maxRetryDelayMs = 30_000L

    fun connect() {
        manuallyClosed = false
        openSocket()
    }

    /** Graceful disconnect — spec requires this be distinct from a dropped connection. */
    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close(1000, "User disconnected")
        socket = null
        onStateChanged(ConnectionState.DISCONNECTED)
    }

    private fun openSocket() {
        onStateChanged(ConnectionState.CONNECTING)
        val scheme = if (useTls) "wss" else "ws"
        val url = "$scheme://$host/ws/mobile-control?device_token=$deviceToken"
        val request = Request.Builder().url(url).build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryDelayMs = 1_000L
                onStateChanged(ConnectionState.CONNECTED)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
                onStateChanged(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel()
                onStateChanged(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retryDelayMs)
            retryDelayMs = min(retryDelayMs * 2, maxRetryDelayMs)
            if (!manuallyClosed) openSocket()
        }
    }

    private fun handleIncoming(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        val id = msg.optString("id")
        val type = msg.optString("type")
        if (id.isBlank() || type.isBlank()) return   // malformed frame — ignore, never crash the socket

        scope.launch {
            val result = try {
                onCommand(id, type, msg.optJSONObject("payload") ?: JSONObject())
            } catch (e: Exception) {
                CommandResult(success = false, error = e.message ?: "Unknown error")
            }
            val response = JSONObject().apply {
                put("id", id)
                put("type", "response")
                put("success", result.success)
                result.result?.let { put("result", it) }
                result.error?.let { put("error", it) }
            }
            webSocket.send(response.toString())
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val battery = currentBatteryPercent()
                val hb = JSONObject().apply {
                    put("type", "heartbeat")
                    if (battery != null) put("battery", battery)
                }
                webSocket.send(hb.toString())
                delay(15_000)
            }
        }
    }

    private fun currentBatteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale)
    }
}
