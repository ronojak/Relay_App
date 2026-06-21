package com.noahlangat.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.noahlangat.relay.R
import com.noahlangat.relay.bluetooth.BluetoothManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that hosts the [RelayEngine].
 *
 * Responsibilities are intentionally narrow: Android service lifecycle, the
 * ongoing notification, and exposing the engine's observable state to bound
 * clients. All relay logic lives in [RelayEngine].
 */
@AndroidEntryPoint
class RelayService : Service() {

    @Inject
    lateinit var bluetoothManager: BluetoothManager

    @Inject
    lateinit var engine: RelayEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoStopJob: Job? = null

    // Re-exposed engine flows for bound clients (see RelayServiceConnection).
    val serviceState: StateFlow<RelayEngine.State> get() = engine.state
    val serviceStats: StateFlow<RelayEngine.Stats> get() = engine.stats
    val logMessages: StateFlow<List<com.noahlangat.relay.ui.components.LogMessage>> get() = engine.logMessages

    private val binder = RelayServiceBinder()

    inner class RelayServiceBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("RelayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("RelayService onStartCommand - action: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP_RELAY -> stopRelayService()
            ACTION_RESTART_RELAY -> restartRelayService()
            else -> startRelayService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startRelayService() {
        if (engine.state.value == RelayEngine.State.RUNNING) {
            Timber.w("RelayService already running")
            return
        }

        startForeground(NOTIFICATION_ID, createForegroundNotification())
        beginRelay()
    }

    private fun stopRelayService() {
        if (engine.state.value == RelayEngine.State.STOPPED) return

        autoStopJob?.cancel()
        autoStopJob = null
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Apply a changed primary configuration by rebuilding the relay in place. */
    private fun restartRelayService() {
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        engine.stop()
        beginRelay()
    }

    private fun beginRelay() {
        engine.start()
        startNotificationUpdates()
        // Stop the whole service if the relay errors out (e.g. primary server
        // offline) — there's nothing to relay to.
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            engine.state.collect { state ->
                if (state == RelayEngine.State.ERROR) {
                    Timber.w("Relay entered ERROR — stopping service")
                    stopRelayService()
                }
            }
        }
    }

    private fun startNotificationUpdates() {
        serviceScope.launch {
            while (engine.state.value == RelayEngine.State.RUNNING ||
                engine.state.value == RelayEngine.State.STARTING
            ) {
                updateNotification()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gamepad Relay Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing gamepad relay service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        val stopIntent = Intent(this, RelayService::class.java).apply {
            action = ACTION_STOP_RELAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gamepad Relay Active")
            .setContentText("Relaying gamepad input to network clients")
            .setSmallIcon(R.drawable.ic_gamepad)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val stats = engine.stats.value
        val uptime = System.currentTimeMillis() - stats.uptime
        val uptimeText = formatUptime(uptime)

        val contentText = if (stats.networkClients > 0) {
            "Active • $uptimeText • ${stats.packetsRelayed} packets"
        } else {
            "No clients • $uptimeText"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gamepad Relay Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun formatUptime(uptimeMs: Long): String {
        val seconds = uptimeMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /** Delegated to the engine; kept for the bound-client diagnostics path. */
    fun testLogMessage() = engine.testLogMessage()

    override fun onDestroy() {
        super.onDestroy()
        engine.shutdown()
        bluetoothManager.cleanup()
        serviceScope.cancel()
        Timber.i("RelayService destroyed")
    }

    companion object {
        private const val CHANNEL_ID = "relay_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RELAY = "com.noahlangat.relay.START_RELAY"
        const val ACTION_STOP_RELAY = "com.noahlangat.relay.STOP_RELAY"
        const val ACTION_RESTART_RELAY = "com.noahlangat.relay.RESTART_RELAY"
    }
}
