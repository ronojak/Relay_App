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
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import com.noahlangat.relay.network.TcpServer
import com.noahlangat.relay.protocol.MessageSerializer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that manages the gamepad relay functionality
 */
@AndroidEntryPoint
class RelayService : Service() {
    
    @Inject
    lateinit var bluetoothManager: BluetoothManager
    
    private lateinit var gamepadInputHandler: GamepadInputHandler
    private lateinit var tcpServer: TcpServer
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var relayJob: Job? = null
    
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState: StateFlow<ServiceState> = _serviceState
    
    private val _serviceStats = MutableStateFlow(ServiceStats())
    val serviceStats: StateFlow<ServiceStats> = _serviceStats
    
    private val binder = RelayServiceBinder()
    
    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
    
    data class ServiceStats(
        val uptime: Long = 0,
        val packetsRelayed: Long = 0,
        val connectedDevices: Int = 0,
        val networkClients: Int = 0,
        val errorCount: Int = 0,
        val averageLatency: Float = 0f
    )
    
    inner class RelayServiceBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        gamepadInputHandler = GamepadInputHandler()
        tcpServer = TcpServer(scope = serviceScope)
        
        createNotificationChannel()
        Timber.i("RelayService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RELAY -> startRelayService()
            ACTION_STOP_RELAY -> stopRelayService()
            else -> startRelayService() // Default action
        }
        
        return START_STICKY // Restart service if killed
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    private fun startRelayService() {
        if (_serviceState.value == ServiceState.RUNNING) {
            Timber.w("RelayService already running")
            return
        }
        
        _serviceState.value = ServiceState.STARTING
        
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        startRelay()
    }
    
    private fun stopRelayService() {
        if (_serviceState.value == ServiceState.STOPPED) return
        
        _serviceState.value = ServiceState.STOPPING
        stopRelay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun startRelay() {
        relayJob = serviceScope.launch {
            try {
                Timber.i("Starting gamepad relay")
                
                // Start TCP server
                if (!tcpServer.start()) {
                    throw IllegalStateException("Failed to start TCP server")
                }
                
                // Monitor gamepad input and relay to network clients
                gamepadInputHandler.gamepadStateFlow.collect { gamepadState ->
                    try {
                        val message = MessageSerializer.serializeGamepadMessage(
                            gamepadState,
                            tcpServer.getNextSequenceNumber()
                        )
                        
                        val success = tcpServer.broadcastGamepadState(message)
                        if (success) {
                            updateStats { stats ->
                                stats.copy(packetsRelayed = stats.packetsRelayed + 1)
                            }
                        }
                        
                        Timber.v("Relayed gamepad state: ${gamepadState.buttons}")
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Error relaying gamepad state")
                        updateStats { stats ->
                            stats.copy(errorCount = stats.errorCount + 1)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Relay service error")
                _serviceState.value = ServiceState.ERROR
                updateStats { stats ->
                    stats.copy(errorCount = stats.errorCount + 1)
                }
            }
        }
        
        // Start monitoring loops
        startMonitoringLoops()
        
        _serviceState.value = ServiceState.RUNNING
        updateStats { stats ->
            stats.copy(uptime = System.currentTimeMillis())
        }
        
        Timber.i("Gamepad relay service started")
    }
    
    private fun stopRelay() {
        relayJob?.cancel()
        
        serviceScope.launch {
            tcpServer.stop()
        }
        
        _serviceState.value = ServiceState.STOPPED
        Timber.i("Gamepad relay service stopped")
    }
    
    private fun startMonitoringLoops() {
        // Monitor Bluetooth devices
        serviceScope.launch {
            bluetoothManager.connectedDevices.collect { devices ->
                updateStats { stats ->
                    stats.copy(connectedDevices = devices.size)
                }
            }
        }
        
        // Monitor network connections
        serviceScope.launch {
            tcpServer.serverState.collect { serverState ->
                val clientCount = if (serverState == TcpServer.ServerState.CLIENT_CONNECTED) 1 else 0
                updateStats { stats ->
                    stats.copy(networkClients = clientCount)
                }
            }
        }
        
        // Update notification periodically
        serviceScope.launch {
            while (_serviceState.value == ServiceState.RUNNING) {
                updateNotification()
                delay(5000) // Update every 5 seconds
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
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
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
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
    
    private fun updateNotification() {
        val stats = _serviceStats.value
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
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
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
    
    private inline fun updateStats(update: (ServiceStats) -> ServiceStats) {
        _serviceStats.value = update(_serviceStats.value)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        bluetoothManager.cleanup()
        serviceScope.cancel()
        
        Timber.i("RelayService destroyed")
    }
    
    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "relay_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_RELAY = "com.noahlangat.relay.START_RELAY"
        const val ACTION_STOP_RELAY = "com.noahlangat.relay.STOP_RELAY"
    }
}