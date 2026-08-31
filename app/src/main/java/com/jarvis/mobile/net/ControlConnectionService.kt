package com.jarvis.mobile.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service so the control WebSocket survives the app being
 * backgrounded — a phone the user just told JARVIS to "open WhatsApp on"
 * is very likely about to have its screen turned off or the app swiped
 * away, and the connection must not die at that moment.
 */
class ControlConnectionService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private var connection: ControlConnection? = null
    private lateinit var dispatcher: CommandDispatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dispatcher = CommandDispatcher(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Connecting to JARVIS…"))

        val pairing = PairingStore(applicationContext).load()
        if (pairing == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        connection?.disconnect()
        connection = ControlConnection(
            context = applicationContext,
            host = pairing.host,
            useTls = pairing.useTls,
            deviceToken = pairing.deviceToken,
            scope = scope,
            onCommand = { _, type, payload -> dispatcher.dispatch(type, payload) },
            onStateChanged = { state -> updateNotification(state) },
        ).also { it.connect() }

        return START_STICKY
    }

    override fun onDestroy() {
        connection?.disconnect()
        job.cancel()
        super.onDestroy()
    }

    private fun updateNotification(state: ControlConnection.ConnectionState) {
        val text = when (state) {
            ControlConnection.ConnectionState.CONNECTED    -> "Connected to JARVIS"
            ControlConnection.ConnectionState.CONNECTING   -> "Connecting to JARVIS…"
            ControlConnection.ConnectionState.DISCONNECTED -> "Disconnected — retrying…"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS Connection", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Mobile")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_control_connection"
        private const val NOTIF_ID = 7
    }
}
