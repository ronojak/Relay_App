package com.noahlangat.relay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.data.RelaySettingsRepository
import com.noahlangat.relay.data.SinkType
import com.noahlangat.relay.data.ThemeMode
import com.noahlangat.relay.protocol.ProtocolConstants
import com.noahlangat.relay.telemetry.TelemetryRepository
import com.noahlangat.relay.ui.components.LogMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val bluetoothManager: BluetoothManager,
    private val settingsRepository: RelaySettingsRepository,
    private val telemetryRepository: TelemetryRepository
) : ViewModel() {

    // Telemetry capture state, exposed directly (not folded into uiState to avoid
    // re-emitting the whole UI state on every captured frame).
    val telemetryEnabled: StateFlow<Boolean> = telemetryRepository.enabled
    val telemetryEvents = telemetryRepository.events

    fun setTelemetryEnabled(enabled: Boolean) = telemetryRepository.setEnabled(enabled)
    fun clearTelemetry() = telemetryRepository.clear()

    val themeMode: StateFlow<ThemeMode> = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) = settingsRepository.update(themeMode = mode)
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState
    
    private var selectedDeviceId: Int? = null
    
    init {
        Timber.w("=== MainViewModel INIT START ===")
        // Collect Bluetooth state and all available devices (connected + discovered)
        viewModelScope.launch {
            combine(
                bluetoothManager.bluetoothState,
                bluetoothManager.connectedDevices,
                bluetoothManager.discoveredDevices,
                bluetoothManager.isDiscovering
            ) { bluetoothState, connectedDevices, discoveredDevices, isDiscovering ->
                Timber.w("=== ViewModel state update: BT=${bluetoothState}, connected=${connectedDevices.size}, discovered=${discoveredDevices.size}, discovering=${isDiscovering} ===")
                // Merge connected and discovered devices
                val allDevices = bluetoothManager.getAllAvailableDevices()
                
                _uiState.value = _uiState.value.copy(
                    bluetoothEnabled = bluetoothState == BluetoothManager.BluetoothState.ENABLED,
                    connectedDevices = allDevices,
                    isDiscovering = isDiscovering
                )
            }
        }

        // Mirror persisted relay settings into UI state.
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _uiState.value = _uiState.value.copy(
                    sinkType = s.sinkType,
                    primaryHost = s.primaryHost,
                    primaryPort = s.primaryPort,
                    serverPort = s.primaryPort.toString()
                )
            }
        }
        Timber.w("=== MainViewModel INIT END ===")
    }

    /** Persist a new primary-connection configuration. Caller restarts the relay to apply. */
    fun applyPrimarySettings(sinkType: SinkType, host: String, port: Int) {
        Timber.i("Applying primary settings: sink=$sinkType host=$host port=$port")
        settingsRepository.update(sinkType = sinkType, host = host, port = port)
    }
    
    fun onBluetoothPermissionGranted() {
        Timber.w("=== BLUETOOTH PERMISSION GRANTED ===")
        // Initialize devices properly after permission is granted
        bluetoothManager.initializeDevices()
        // Load paired devices 
        refreshPairedDevices()
    }
    
    fun refreshPairedDevices() {
        Timber.w("=== REFRESH PAIRED DEVICES START ===")
        val pairedDevices = bluetoothManager.getPairedGamepadDevices()
        Timber.w("Found ${pairedDevices.size} paired gamepad devices")
        pairedDevices.forEach { device ->
            Timber.w("Paired device: ${device.name} (${device.deviceType}) - ${device.address}")
        }
        Timber.w("=== REFRESH PAIRED DEVICES END ===")
    }
    
    fun onBluetoothPermissionDenied() {
        Timber.w("Bluetooth permission denied")
        _uiState.value = _uiState.value.copy(
            bluetoothEnabled = false,
            permissionDenied = true
        )
    }
    
    fun startDiscovery() {
        viewModelScope.launch {
            val started = bluetoothManager.startDiscovery()
            if (!started) {
                Timber.w("Failed to start Bluetooth discovery")
            }
        }
    }
    
    fun stopDiscovery() {
        viewModelScope.launch {
            bluetoothManager.stopDiscovery()
        }
    }
    
    fun refreshDevices() {
        Timber.i("=== REFRESH DEVICES CALLED ===")
        bluetoothManager.scanForConnectedGamepads()
        refreshPairedDevices()
        startDiscovery()
        
        // Force immediate UI update with current devices
        viewModelScope.launch {
            val allDevices = bluetoothManager.getAllAvailableDevices()
            Timber.i("Force updating UI with ${allDevices.size} devices")
            _uiState.value = _uiState.value.copy(
                connectedDevices = allDevices
            )
        }
    }
    
    fun selectDevice(deviceId: Int) {
        selectedDeviceId = deviceId
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId)
        val device = _uiState.value.connectedDevices.find { it.id == deviceId }
        Timber.w("Selected device: ${device?.name} (ID: $deviceId)")
    }
    
    fun connectToDevice() {
        selectedDeviceId?.let { deviceId ->
            val device = _uiState.value.connectedDevices.find { it.id == deviceId }
            Timber.w("=== CONNECT BUTTON PRESSED for device: ${device?.name} (ID: $deviceId) ===")
            bluetoothManager.connectToDevice(deviceId)
            
            // Force UI update
            viewModelScope.launch {
                val allDevices = bluetoothManager.getAllAvailableDevices()
                val connectedCount = bluetoothManager.getConnectedDeviceCount()
                
                _uiState.value = _uiState.value.copy(
                    connectedDevices = allDevices
                )
                
                Timber.w("Individual connect: UI updated with ${allDevices.size} devices, ${connectedCount} connected")
            }
        } ?: run {
            // No device selected, connect to all devices
            Timber.w("No device selected, connecting to all devices")
            connectToAllDevices()
        }
    }
    
    fun connectToAllDevices() {
        Timber.w("=== CONNECT TO ALL DEVICES REQUESTED ===")
        bluetoothManager.connectToAllDevices()
        
        // Force UI update
        viewModelScope.launch {
            val allDevices = bluetoothManager.getAllAvailableDevices()
            val connectedCount = bluetoothManager.getConnectedDeviceCount()
            
            _uiState.value = _uiState.value.copy(
                connectedDevices = allDevices
            )
            
            Timber.w("UI updated with ${allDevices.size} devices, ${connectedCount} connected")
        }
    }
    
    fun disconnectFromDevice() {
        selectedDeviceId?.let { deviceId ->
            Timber.w("Disconnect button pressed for device: $deviceId")
            bluetoothManager.disconnectFromDevice(deviceId)
        } ?: run {
            // No device selected, disconnect from all devices
            Timber.w("No device selected, disconnecting from all devices")
            disconnectFromAllDevices()
        }
    }
    
    fun disconnectFromAllDevices() {
        Timber.w("=== DISCONNECT FROM ALL DEVICES REQUESTED ===")
        bluetoothManager.disconnectFromAllDevices()
        
        // Force UI update  
        viewModelScope.launch {
            val allDevices = bluetoothManager.getAllAvailableDevices()
            val connectedCount = bluetoothManager.getConnectedDeviceCount()
            
            _uiState.value = _uiState.value.copy(
                connectedDevices = allDevices
            )
            
            Timber.w("UI updated with ${allDevices.size} devices, ${connectedCount} connected")
        }
    }
    fun setServiceRunning(running: Boolean) {
        _uiState.value = _uiState.value.copy(isServiceRunning = running)
    }

    fun setClientInfo(info: String?) {
        _uiState.value = _uiState.value.copy(clientInfo = info)
    }

    fun updatePerformanceStats(
        packetsTransmitted: Long,
        packetsDropped: Long,
        currentHz: Float,
        averageLatency: Float,
        uptime: String
    ) {
        _uiState.value = _uiState.value.copy(
            packetsTransmitted = packetsTransmitted,
            packetsDropped = packetsDropped,
            currentHz = currentHz,
            averageLatency = averageLatency,
            p99Latency = averageLatency * 1.5f, // Rough estimate for p99
            uptime = uptime
        )
    }
    
    fun updateLogMessages(logMessages: List<LogMessage>) {
        _uiState.value = _uiState.value.copy(logMessages = logMessages)
    }
    
    fun clearLogMessages() {
        _uiState.value = _uiState.value.copy(logMessages = emptyList())
    }
}

data class MainUiState(
    val bluetoothEnabled: Boolean = false,
    val permissionDenied: Boolean = false,
    val connectedDevices: List<BluetoothManager.GamepadDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val serverPort: String = "26543",
    val sinkType: SinkType = SinkType.WIFI_CLIENT,
    val primaryHost: String = "",
    val primaryPort: Int = ProtocolConstants.DEFAULT_TCP_PORT,
    val clientInfo: String? = null,
    val packetsTransmitted: Long = 0L,
    val packetsDropped: Long = 0L,
    val currentHz: Float = 0f,
    val averageLatency: Float = 0f,
    val p99Latency: Float = 0f,
    val uptime: String = "00:00:00",
    val isServiceRunning: Boolean = false,
    val selectedDeviceId: Int? = null,
    val logMessages: List<LogMessage> = emptyList()
)
