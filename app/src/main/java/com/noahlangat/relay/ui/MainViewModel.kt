package com.noahlangat.relay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noahlangat.relay.bluetooth.BluetoothManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val bluetoothManager: BluetoothManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState
    
    private var selectedDeviceId: Int? = null
    
    init {
        // Collect Bluetooth state and connected devices
        viewModelScope.launch {
            combine(
                bluetoothManager.bluetoothState,
                bluetoothManager.connectedDevices
            ) { bluetoothState, connectedDevices ->
                _uiState.value = _uiState.value.copy(
                    bluetoothEnabled = bluetoothState == BluetoothManager.BluetoothState.ENABLED,
                    connectedDevices = connectedDevices
                )
            }
        }
    }
    
    fun onBluetoothPermissionGranted() {
        Timber.i("Bluetooth permission granted")
        bluetoothManager.scanForConnectedGamepads()
    }
    
    fun onBluetoothPermissionDenied() {
        Timber.w("Bluetooth permission denied")
        _uiState.value = _uiState.value.copy(
            bluetoothEnabled = false,
            permissionDenied = true
        )
    }
    
    fun selectDevice(deviceId: Int) {
        selectedDeviceId = deviceId
        val device = _uiState.value.connectedDevices.find { it.id == deviceId }
        Timber.i("Selected device: ${device?.name}")
    }
    
    fun updatePort(port: String) {
        _uiState.value = _uiState.value.copy(serverPort = port)
    }
    
    fun connectToDevice() {
        selectedDeviceId?.let { deviceId ->
            // Implementation would connect to the selected device
            Timber.i("Connecting to device: $deviceId")
        }
    }
    
    fun disconnectFromDevice() {
        // Implementation would disconnect from current device
        Timber.i("Disconnecting from device")
    }
    fun setServiceRunning(running: Boolean) {
        _uiState.value = _uiState.value.copy(isServiceRunning = running)
    }

    fun setClientInfo(info: String?) {
        _uiState.value = _uiState.value.copy(clientInfo = info)
    }

    // Mock data for UI preview - in real implementation this would come from services
    private fun updateMockStats() {
        _uiState.value = _uiState.value.copy(
            packetsTransmitted = 15247L,
            packetsDropped = 0L,
            currentHz = 119.8f,
            averageLatency = 23.4f,
            p99Latency = 45.2f,
            uptime = "02:34:18",
            isServiceRunning = true
        )
    }
}

data class MainUiState(
    val bluetoothEnabled: Boolean = false,
    val permissionDenied: Boolean = false,
    val connectedDevices: List<BluetoothManager.GamepadDevice> = emptyList(),
    val serverPort: String = "26543",
    val clientInfo: String? = null,
    val packetsTransmitted: Long = 0L,
    val packetsDropped: Long = 0L,
    val currentHz: Float = 0f,
    val averageLatency: Float = 0f,
    val p99Latency: Float = 0f,
    val uptime: String = "00:00:00",
    val isServiceRunning: Boolean = false
)
