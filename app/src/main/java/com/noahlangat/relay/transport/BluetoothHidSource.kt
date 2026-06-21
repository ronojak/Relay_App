package com.noahlangat.relay.transport

import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import com.noahlangat.relay.protocol.GamepadState
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Secondary/input transport for local Bluetooth gamepads. Frames arrive as
 * Android HID input events (surfaced by [GamepadInputHandler]); device
 * connect/disconnect is tracked via [BluetoothManager].
 */
class BluetoothHidSource(
    private val bluetoothManager: BluetoothManager,
    private val gamepadInputHandler: GamepadInputHandler
) : SourceTransport {

    override val id = "bluetooth-hid"
    override val displayName = "Bluetooth Gamepad"

    override val frames: Flow<GamepadState> = gamepadInputHandler.gamepadStateFlow

    private val _state = MutableStateFlow(LinkState.IDLE)
    override val state: StateFlow<LinkState> = _state

    private val _activeInputs = MutableStateFlow(0)
    override val activeInputs: StateFlow<Int> = _activeInputs

    private val _logs = MutableSharedFlow<LogMessage>(extraBufferCapacity = 64)
    override val logs: SharedFlow<LogMessage> = _logs

    private var monitorJob: Job? = null

    override fun start(scope: CoroutineScope) {
        _state.value = LinkState.READY

        monitorJob = scope.launch {
            // Forward low-level input-handler logs.
            launch { gamepadInputHandler.logMessageFlow.collect { _logs.tryEmit(it) } }

            // Track connected controllers and mirror the previous connect/disconnect logging.
            var previousDeviceCount = 0
            bluetoothManager.connectedDevices.collect { devices ->
                _activeInputs.value = devices.size
                _state.value = if (devices.isNotEmpty()) LinkState.ACTIVE else LinkState.READY

                if (devices.size != previousDeviceCount) {
                    if (devices.size > previousDeviceCount) {
                        devices.takeLast(devices.size - previousDeviceCount).forEach { device ->
                            _logs.tryEmit(
                                LogMessage(
                                    message = "Bluetooth device connected",
                                    level = LogLevel.INFO,
                                    deviceName = device.name,
                                    deviceId = device.id,
                                    source = LogSource.BLUETOOTH
                                )
                            )
                        }
                    } else {
                        _logs.tryEmit(
                            LogMessage(
                                message = "${previousDeviceCount - devices.size} Bluetooth device(s) disconnected",
                                level = LogLevel.INFO,
                                source = LogSource.BLUETOOTH
                            )
                        )
                    }
                    previousDeviceCount = devices.size
                }
            }
        }
    }

    override fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = LinkState.IDLE
    }
}
