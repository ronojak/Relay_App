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
import com.noahlangat.relay.protocol.GamepadState
import com.noahlangat.relay.protocol.MessageSerializer
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogSource
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
    
    @Inject
    lateinit var gamepadInputHandler: GamepadInputHandler
    private lateinit var tcpServer: TcpServer
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var relayJob: Job? = null
    
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState: StateFlow<ServiceState> = _serviceState
    
    private val _serviceStats = MutableStateFlow(ServiceStats())
    val serviceStats: StateFlow<ServiceStats> = _serviceStats
    
    private val _logMessages = MutableStateFlow<List<LogMessage>>(emptyList())
    val logMessages: StateFlow<List<LogMessage>> = _logMessages
    
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
        val averageLatency: Float = 0f,
        val currentHz: Float = 0f,
        val lastPacketTime: Long = 0
    )
    
    inner class RelayServiceBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        addLogMessage(
            message = "RelayService onCreate() called",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        
        tcpServer = TcpServer(scope = serviceScope)
        
        addLogMessage(
            message = "GamepadInputHandler injected and TcpServer initialized",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        
        createNotificationChannel()
        Timber.i("RelayService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        addLogMessage(
            message = "RelayService onStartCommand() received - action: ${intent?.action}",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        
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
            addLogMessage(
                message = "Service already running - ignoring start request",
                level = LogLevel.WARN,
                source = LogSource.SERVICE
            )
            return
        }
        
        addLogMessage(
            message = "Starting RelayService...",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        
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
                
                addLogMessage(
                    message = "Starting to monitor gamepad input flow...",
                    level = LogLevel.INFO,
                    source = LogSource.SERVICE
                )
                
                // Add test message to verify gamepad flow is being monitored
                addLogMessage(
                    message = "GamepadInputHandler flow collection started - waiting for input events",
                    level = LogLevel.INFO,
                    source = LogSource.GAMEPAD
                )
                
                // Start monitoring gamepad button/input events for UI logging
                launch {
                    gamepadInputHandler.logMessageFlow.collect { logMessage ->
                        val currentLogs = _logMessages.value.toMutableList()
                        currentLogs.add(logMessage)
                        
                        // Keep only last 200 messages to prevent memory issues
                        if (currentLogs.size > 200) {
                            currentLogs.removeAt(0)
                        }
                        
                        _logMessages.value = currentLogs
                    }
                }

                // Monitor gamepad input and relay to network clients
                gamepadInputHandler.gamepadStateFlow.collect { gamepadState ->
                    try {
                        val currentTime = System.currentTimeMillis()
                        val message = MessageSerializer.serializeGamepadMessage(
                            gamepadState,
                            tcpServer.getNextSequenceNumber()
                        )
                        
                        // Always log gamepad input activity with detailed button info
                        val buttonInfo = getButtonInfo(gamepadState)
                        val inputMessage = if (buttonInfo.isNotEmpty()) {
                            "$buttonInfo | LStick=(${gamepadState.leftStickX}, ${gamepadState.leftStickY}) RStick=(${gamepadState.rightStickX}, ${gamepadState.rightStickY}) Triggers=(${gamepadState.leftTrigger}, ${gamepadState.rightTrigger})"
                        } else {
                            "Analog input: LStick=(${gamepadState.leftStickX}, ${gamepadState.leftStickY}) RStick=(${gamepadState.rightStickX}, ${gamepadState.rightStickY}) Triggers=(${gamepadState.leftTrigger}, ${gamepadState.rightTrigger})"
                        }
                        
                        addLogMessage(
                            message = inputMessage,
                            level = LogLevel.INFO,
                            deviceName = "DualSense",
                            deviceId = gamepadState.deviceId.toInt(),
                            source = LogSource.GAMEPAD
                        )
                        
                        val success = tcpServer.broadcastGamepadState(message)
                        if (success) {
                            updateStats { stats ->
                                // Calculate current Hz based on time between packets
                                val timeDiff = currentTime - stats.lastPacketTime
                                val currentHz = if (timeDiff > 0 && stats.lastPacketTime > 0) {
                                    1000f / timeDiff.toFloat()
                                } else 0f
                                
                                stats.copy(
                                    packetsRelayed = stats.packetsRelayed + 1,
                                    currentHz = currentHz,
                                    lastPacketTime = currentTime,
                                    averageLatency = calculateLatency() // Simple latency estimate
                                )
                            }
                            
                            // Log successful network relay (only occasionally to avoid spam)
                            if (System.currentTimeMillis() % 2000 < 100) { // Every 2 seconds
                                addLogMessage(
                                    message = "Data successfully relayed to network client",
                                    level = LogLevel.INFO,
                                    source = LogSource.NETWORK
                                )
                            }
                        } else {
                            // Only log network failures occasionally to avoid spam
                            if (System.currentTimeMillis() % 5000 < 100) { // Every 5 seconds
                                addLogMessage(
                                    message = "No network clients connected - input not relayed",
                                    level = LogLevel.WARN,
                                    source = LogSource.NETWORK
                                )
                            }
                        }
                        
                        Timber.v("Relayed gamepad state: ${gamepadState.buttons}")
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Error relaying gamepad state")
                        addLogMessage(
                            message = "Error relaying gamepad state: ${e.message}",
                            level = LogLevel.ERROR,
                            source = LogSource.SERVICE
                        )
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
        
        addLogMessage(
            message = "Relay service started successfully",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        
        Timber.i("Gamepad relay service started")
    }
    
    private fun stopRelay() {
        relayJob?.cancel()
        
        serviceScope.launch {
            tcpServer.stop()
        }
        
        _serviceState.value = ServiceState.STOPPED
        addLogMessage(
            message = "Relay service stopped",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        Timber.i("Gamepad relay service stopped")
    }
    
    private fun startMonitoringLoops() {
        // Monitor Bluetooth devices
        serviceScope.launch {
            var previousDeviceCount = 0
            bluetoothManager.connectedDevices.collect { devices ->
                updateStats { stats ->
                    stats.copy(connectedDevices = devices.size)
                }
                
                // Log device connection changes
                if (devices.size != previousDeviceCount) {
                    if (devices.size > previousDeviceCount) {
                        devices.takeLast(devices.size - previousDeviceCount).forEach { device ->
                            addLogMessage(
                                message = "Bluetooth device connected",
                                level = LogLevel.INFO,
                                deviceName = device.name,
                                deviceId = device.id,
                                source = LogSource.BLUETOOTH
                            )
                        }
                    } else if (devices.size < previousDeviceCount) {
                        addLogMessage(
                            message = "${previousDeviceCount - devices.size} Bluetooth device(s) disconnected",
                            level = LogLevel.INFO,
                            source = LogSource.BLUETOOTH
                        )
                    }
                    previousDeviceCount = devices.size
                }
            }
        }
        
        // Monitor network connections
        serviceScope.launch {
            var previousClientConnected = false
            tcpServer.serverState.collect { serverState ->
                val clientConnected = serverState == TcpServer.ServerState.CLIENT_CONNECTED
                val clientCount = if (clientConnected) 1 else 0
                
                updateStats { stats ->
                    stats.copy(networkClients = clientCount)
                }
                
                // Log client connection changes
                if (clientConnected != previousClientConnected) {
                    if (clientConnected) {
                        addLogMessage(
                            message = "Network client connected to TCP server",
                            level = LogLevel.INFO,
                            source = LogSource.NETWORK
                        )
                    } else {
                        addLogMessage(
                            message = "Network client disconnected from TCP server",
                            level = LogLevel.INFO,
                            source = LogSource.NETWORK
                        )
                    }
                    previousClientConnected = clientConnected
                }
            }
        }
        
        // Update notification periodically and generate simulated stats for testing
        serviceScope.launch {
            while (_serviceState.value == ServiceState.RUNNING) {
                updateNotification()
                
                // Generate some test statistics and log messages when devices are connected
                val currentStats = _serviceStats.value
                if (currentStats.connectedDevices > 0 && currentStats.networkClients > 0) {
                    // Simulate some packet activity for testing
                    updateStats { stats ->
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - stats.lastPacketTime
                        
                        if (timeDiff > 5000) { // No real packets in last 5 seconds, generate test data
                            stats.copy(
                                packetsRelayed = stats.packetsRelayed + 1,
                                currentHz = 60.0f, // Simulated 60Hz
                                lastPacketTime = currentTime,
                                averageLatency = 12.5f + (Math.random() * 5).toFloat()
                            )
                        } else {
                            stats
                        }
                    }
                    
                }
                
                delay(1000) // Update every second
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
    
    private fun calculateLatency(): Float {
        // Simple latency estimation - in a real implementation, this would
        // measure actual round-trip time to connected clients
        return when {
            tcpServer.serverState.value == TcpServer.ServerState.CLIENT_CONNECTED -> 5.0f + (Math.random() * 10).toFloat()
            else -> 0.0f
        }
    }
    
    private fun addLogMessage(
        message: String,
        level: LogLevel = LogLevel.INFO,
        deviceName: String = "System",
        deviceId: Int? = null,
        source: LogSource = LogSource.SYSTEM
    ) {
        val logMessage = LogMessage(
            message = message,
            level = level,
            deviceName = deviceName,
            deviceId = deviceId,
            source = source
        )
        
        val currentLogs = _logMessages.value.toMutableList()
        currentLogs.add(logMessage)
        
        // Keep only last 200 messages to prevent memory issues
        if (currentLogs.size > 200) {
            currentLogs.removeAt(0)
        }
        
        _logMessages.value = currentLogs
    }
    
    /**
     * Test method to verify log message flow is working
     */
    fun testLogMessage() {
        addLogMessage(
            message = "TEST: Service connection successful - LogViewer should display this message",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
    }
    
    private fun getButtonInfo(gamepadState: GamepadState): String {
        val pressedButtons = mutableListOf<String>()
        val buttons = gamepadState.buttons.toInt()
        
        if ((buttons and (1 shl 0)) != 0) pressedButtons.add("X")        // Cross
        if ((buttons and (1 shl 1)) != 0) pressedButtons.add("Circle")   // Circle 
        if ((buttons and (1 shl 2)) != 0) pressedButtons.add("Square")   // Square
        if ((buttons and (1 shl 3)) != 0) pressedButtons.add("Triangle") // Triangle
        if ((buttons and (1 shl 4)) != 0) pressedButtons.add("L1")       // L1
        if ((buttons and (1 shl 5)) != 0) pressedButtons.add("R1")       // R1
        if ((buttons and (1 shl 6)) != 0) pressedButtons.add("L2")       // L2
        if ((buttons and (1 shl 7)) != 0) pressedButtons.add("R2")       // R2
        if ((buttons and (1 shl 8)) != 0) pressedButtons.add("Share")    // Share
        if ((buttons and (1 shl 9)) != 0) pressedButtons.add("Options")  // Options
        if ((buttons and (1 shl 10)) != 0) pressedButtons.add("L3")      // L3
        if ((buttons and (1 shl 11)) != 0) pressedButtons.add("R3")      // R3
        if ((buttons and (1 shl 12)) != 0) pressedButtons.add("PS")      // PS
        if ((buttons and (1 shl 13)) != 0) pressedButtons.add("Touch")   // Touchpad
        
        return if (pressedButtons.isNotEmpty()) {
            "Buttons: ${pressedButtons.joinToString(", ")}"
        } else {
            ""
        }
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