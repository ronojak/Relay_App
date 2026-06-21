package com.noahlangat.relay.service

import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import com.noahlangat.relay.network.TcpServer
import com.noahlangat.relay.protocol.GamepadState
import com.noahlangat.relay.protocol.MessageSerializer
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the core relay pipeline: pulls gamepad input from the [GamepadInputHandler]
 * (secondary / input source) and pushes serialized frames to the [TcpServer]
 * (primary / output sink), while exposing observable state, stats and logs.
 *
 * This was extracted out of [RelayService] so the foreground service is only
 * responsible for the Android lifecycle and notification, and the relay logic
 * can later be re-pointed at swappable transports (see roadmap).
 */
@Singleton
class RelayEngine @Inject constructor(
    private val bluetoothManager: BluetoothManager,
    private val gamepadInputHandler: GamepadInputHandler
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Single TCP server (primary/output). Created once for the engine's lifetime. */
    private val tcpServer = TcpServer(scope = scope)

    /** Everything spawned by [start]; cancelled wholesale by [stop]. */
    private var runJob: Job? = null

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private val _logMessages = MutableStateFlow<List<LogMessage>>(emptyList())
    val logMessages: StateFlow<List<LogMessage>> = _logMessages

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class Stats(
        val uptime: Long = 0,
        val packetsRelayed: Long = 0,
        val connectedDevices: Int = 0,
        val networkClients: Int = 0,
        val errorCount: Int = 0,
        val averageLatency: Float = 0f,
        val currentHz: Float = 0f,
        val lastPacketTime: Long = 0
    )

    /** Start the relay pipeline. No-op if already running. */
    fun start() {
        if (_state.value == State.RUNNING) {
            Timber.w("RelayEngine already running")
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
        _state.value = State.STARTING

        runJob = scope.launch {
            try {
                Timber.i("Starting gamepad relay")

                if (!tcpServer.start()) {
                    throw IllegalStateException("Failed to start TCP server")
                }

                _state.value = State.RUNNING
                updateStats { it.copy(uptime = System.currentTimeMillis()) }

                addLogMessage(
                    message = "Relay service started successfully",
                    level = LogLevel.INFO,
                    source = LogSource.SERVICE
                )
                addLogMessage(
                    message = "Starting to monitor gamepad input flow...",
                    level = LogLevel.INFO,
                    source = LogSource.SERVICE
                )
                addLogMessage(
                    message = "GamepadInputHandler flow collection started - waiting for input events",
                    level = LogLevel.INFO,
                    source = LogSource.GAMEPAD
                )

                // Forward log messages emitted by GamepadInputHandler.
                launch {
                    gamepadInputHandler.logMessageFlow.collect { logMessage ->
                        appendLog(logMessage)
                    }
                }

                // Track the latest gamepad state and log inputs as they arrive.
                val latestGamepadState = MutableStateFlow<GamepadState?>(null)
                launch {
                    gamepadInputHandler.gamepadStateFlow.collectLatest { gamepadState ->
                        latestGamepadState.value = gamepadState
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
                    }
                }

                // Main relay loop at a fixed frequency.
                launch {
                    while (isActive && _state.value == State.RUNNING) {
                        val gamepadState = latestGamepadState.value
                        if (gamepadState != null) {
                            try {
                                val currentTime = System.currentTimeMillis()
                                val message = MessageSerializer.serializeGamepadMessage(
                                    gamepadState,
                                    tcpServer.getNextSequenceNumber()
                                )
                                val success = tcpServer.broadcastGamepadState(message)
                                if (success) {
                                    updateStats { stats ->
                                        stats.copy(
                                            packetsRelayed = stats.packetsRelayed + 1,
                                            currentHz = RELAY_FREQUENCY_HZ.toFloat(),
                                            lastPacketTime = currentTime,
                                            averageLatency = calculateLatency()
                                        )
                                    }
                                    if (currentTime % 2000 < 100) {
                                        addLogMessage(
                                            message = "Data relayed to network client (fixed ${RELAY_FREQUENCY_HZ}Hz)",
                                            level = LogLevel.INFO,
                                            source = LogSource.NETWORK
                                        )
                                    }
                                } else {
                                    if (currentTime % 5000 < 100) {
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
                                updateStats { it.copy(errorCount = it.errorCount + 1) }
                            }
                        }
                        delay(1000L / RELAY_FREQUENCY_HZ)
                    }
                }

                startMonitoringLoops()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Relay engine error")
                _state.value = State.ERROR
                updateStats { it.copy(errorCount = it.errorCount + 1) }
            }
        }

        Timber.i("Gamepad relay engine started")
    }

    /** Stop the relay pipeline. No-op if already stopped. */
    fun stop() {
        if (_state.value == State.STOPPED) return

        _state.value = State.STOPPING
        runJob?.cancel()
        runJob = null
        scope.launch { tcpServer.stop() }
        _state.value = State.STOPPED

        addLogMessage(
            message = "Relay service stopped",
            level = LogLevel.INFO,
            source = LogSource.SERVICE
        )
        Timber.i("Gamepad relay engine stopped")
    }

    /**
     * Called when the owning service is destroyed. The engine is a process-lifetime
     * singleton, so we only stop the active run (the scope is kept alive so a
     * restarted service can start the engine again).
     */
    fun shutdown() {
        stop()
        Timber.i("RelayEngine shut down")
    }

    fun getCurrentClientInfo(): String? = tcpServer.getCurrentClientInfo()

    private fun CoroutineScope.startMonitoringLoops() {
        // Monitor Bluetooth devices (secondary/input).
        launch {
            var previousDeviceCount = 0
            bluetoothManager.connectedDevices.collect { devices ->
                updateStats { it.copy(connectedDevices = devices.size) }

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
                    } else {
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

        // Monitor network connection (primary/output).
        launch {
            var previousClientConnected = false
            tcpServer.serverState.collect { serverState ->
                val clientConnected = serverState == TcpServer.ServerState.CLIENT_CONNECTED
                updateStats { it.copy(networkClients = if (clientConnected) 1 else 0) }

                if (clientConnected != previousClientConnected) {
                    addLogMessage(
                        message = if (clientConnected) {
                            "Network client connected to TCP server"
                        } else {
                            "Network client disconnected from TCP server"
                        },
                        level = LogLevel.INFO,
                        source = LogSource.NETWORK
                    )
                    previousClientConnected = clientConnected
                }
            }
        }

        // Periodically refresh simulated stats while running.
        launch {
            while (isActive && _state.value == State.RUNNING) {
                val currentStats = _stats.value
                if (currentStats.connectedDevices > 0 && currentStats.networkClients > 0) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - currentStats.lastPacketTime > 5000) {
                        updateStats { stats ->
                            stats.copy(
                                packetsRelayed = stats.packetsRelayed + 1,
                                currentHz = 60.0f,
                                lastPacketTime = currentTime,
                                averageLatency = 12.5f + (Math.random() * 5).toFloat()
                            )
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun calculateLatency(): Float {
        return if (tcpServer.serverState.value == TcpServer.ServerState.CLIENT_CONNECTED) {
            5.0f + (Math.random() * 10).toFloat()
        } else {
            0.0f
        }
    }

    private inline fun updateStats(update: (Stats) -> Stats) {
        _stats.value = update(_stats.value)
    }

    private fun addLogMessage(
        message: String,
        level: LogLevel = LogLevel.INFO,
        deviceName: String = "System",
        deviceId: Int? = null,
        source: LogSource = LogSource.SYSTEM
    ) {
        appendLog(
            LogMessage(
                message = message,
                level = level,
                deviceName = deviceName,
                deviceId = deviceId,
                source = source
            )
        )
    }

    private fun appendLog(logMessage: LogMessage) {
        val currentLogs = _logMessages.value.toMutableList()
        currentLogs.add(logMessage)
        if (currentLogs.size > MAX_LOG_MESSAGES) {
            currentLogs.removeAt(0)
        }
        _logMessages.value = currentLogs
    }

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

        if ((buttons and (1 shl 0)) != 0) pressedButtons.add("X")
        if ((buttons and (1 shl 1)) != 0) pressedButtons.add("Circle")
        if ((buttons and (1 shl 2)) != 0) pressedButtons.add("Square")
        if ((buttons and (1 shl 3)) != 0) pressedButtons.add("Triangle")
        if ((buttons and (1 shl 4)) != 0) pressedButtons.add("L1")
        if ((buttons and (1 shl 5)) != 0) pressedButtons.add("R1")
        if ((buttons and (1 shl 6)) != 0) pressedButtons.add("L2")
        if ((buttons and (1 shl 7)) != 0) pressedButtons.add("R2")
        if ((buttons and (1 shl 8)) != 0) pressedButtons.add("Share")
        if ((buttons and (1 shl 9)) != 0) pressedButtons.add("Options")
        if ((buttons and (1 shl 10)) != 0) pressedButtons.add("L3")
        if ((buttons and (1 shl 11)) != 0) pressedButtons.add("R3")
        if ((buttons and (1 shl 12)) != 0) pressedButtons.add("PS")
        if ((buttons and (1 shl 13)) != 0) pressedButtons.add("Touch")

        return if (pressedButtons.isNotEmpty()) {
            "Buttons: ${pressedButtons.joinToString(", ")}"
        } else {
            ""
        }
    }

    companion object {
        private const val RELAY_FREQUENCY_HZ = 10
        private const val MAX_LOG_MESSAGES = 200
    }
}
