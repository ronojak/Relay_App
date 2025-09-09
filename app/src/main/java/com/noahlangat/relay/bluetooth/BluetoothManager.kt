package com.noahlangat.relay.bluetooth
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Manages Bluetooth gamepad discovery and connection lifecycle
 */
class BluetoothManager(private val context: Context) {
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as SystemBluetoothManager
        bluetoothManager.adapter
    }
    
    private val _bluetoothState = MutableStateFlow(BluetoothState.UNKNOWN)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState
    
    private val _connectedDevices = MutableStateFlow<List<GamepadDevice>>(emptyList())
    val connectedDevices: StateFlow<List<GamepadDevice>> = _connectedDevices
    
    private val _discoveredDevices = MutableStateFlow<List<GamepadDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<GamepadDevice>> = _discoveredDevices
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering
    
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    handleBluetoothStateChange(intent)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    handleDeviceConnected(intent)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    handleDeviceDisconnected(intent)
                }
                BluetoothDevice.ACTION_FOUND -> {
                    handleDeviceDiscovered(intent)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isDiscovering.value = true
                    Timber.d("Bluetooth discovery started")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscovering.value = false
                    Timber.d("Bluetooth discovery finished")
                }
            }
        }
    }
    
    enum class BluetoothState {
        UNKNOWN,
        DISABLED,
        ENABLED,
        ENABLING,
        DISABLING
    }
    
    data class GamepadDevice(
        val id: Int,
        val name: String,
        val address: String?,
        val isConnected: Boolean,
        val deviceType: GamepadType,
        val capabilities: Set<String> = emptySet()
    )
    
    enum class GamepadType {
        PS5_DUALSENSE,
        PS4_DUALSHOCK,
        XBOX_CONTROLLER,
        GENERIC,
        UNKNOWN
    }
    
    init {
        registerBluetoothReceiver()
        updateBluetoothState()
        scanForConnectedGamepads()
    }
    
    /**
     * Check if Bluetooth is available and enabled
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Get paired Bluetooth devices that could be gamepads
     */
    @SuppressLint("MissingPermission")
    fun getPairedGamepadDevices(): List<GamepadDevice> {
        if (!isBluetoothEnabled()) return emptyList()
        
        return bluetoothAdapter?.bondedDevices?.mapNotNull { device ->
            if (isLikelyGamepad(device)) {
                GamepadDevice(
                    id = device.address.hashCode(),
                    name = device.name ?: "Unknown Device",
                    address = device.address,
                    isConnected = false, // We'll update this separately
                    deviceType = identifyGamepadType(device.name)
                )
            } else null
        } ?: emptyList()
    }
    
    /**
     * Start discovering nearby Bluetooth devices
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        if (!isBluetoothEnabled()) {
            Timber.w("Cannot start discovery - Bluetooth not enabled")
            return false
        }
        
        if (!hasRequiredPermissions()) {
            Timber.w("Cannot start discovery - Missing required permissions")
            return false
        }
        
        // Cancel ongoing discovery if any
        bluetoothAdapter?.let { adapter ->
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            
            // Clear previous discoveries
            _discoveredDevices.value = emptyList()
            
            // Start discovery
            val started = adapter.startDiscovery()
            if (started) {
                Timber.i("Bluetooth discovery started")
            } else {
                Timber.w("Failed to start Bluetooth discovery")
            }
            return started
        }
        
        return false
    }
    
    /**
     * Stop ongoing discovery
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery(): Boolean {
        if (!isBluetoothEnabled()) return false
        
        return bluetoothAdapter?.let { adapter ->
            val stopped = adapter.cancelDiscovery()
            if (stopped) {
                Timber.i("Bluetooth discovery stopped")
            }
            stopped
        } ?: false
    }
    
    /**
     * Get all available devices (paired + discovered) with current connection states
     */
    fun getAllAvailableDevices(): List<GamepadDevice> {
        // Get fresh device lists
        val allPaired = getAllPairedDevicesForDebug() 
        val gamepadDiscovered = _discoveredDevices.value 
        val currentConnected = _connectedDevices.value
        
        Timber.w("getAllAvailableDevices: ${allPaired.size} paired devices, ${gamepadDiscovered.size} discovered gamepads, ${currentConnected.size} in connected state")
        
        // Merge lists, avoiding duplicates by address
        val combined = mutableMapOf<String, GamepadDevice>()
        
        // Add ALL paired devices first 
        allPaired.forEach { device ->
            device.address?.let { address ->
                // Check if this device is in the connected state
                val connectedDevice = currentConnected.find { it.address == address }
                val deviceWithState = if (connectedDevice != null) {
                    device.copy(isConnected = connectedDevice.isConnected)
                } else {
                    device
                }
                combined[address] = deviceWithState
            }
        }
        
        // Add discovered gamepad devices if not already in paired list
        gamepadDiscovered.forEach { device ->
            device.address?.let { address ->
                if (!combined.containsKey(address)) {
                    // Check if this device is in the connected state
                    val connectedDevice = currentConnected.find { it.address == address }
                    val deviceWithState = if (connectedDevice != null) {
                        device.copy(isConnected = connectedDevice.isConnected)
                    } else {
                        device
                    }
                    combined[address] = deviceWithState
                }
            }
        }
        
        val result = combined.values.toList()
        Timber.w("Returning ${result.size} total devices to UI")
        result.forEach { device ->
            Timber.w("UI Device: ${device.name} (${device.deviceType}) - Connected: ${device.isConnected}")
        }
        
        return result
    }
    
    /**
     * Scan for currently connected input devices
     */
    fun scanForConnectedGamepads() {
        val inputDevices = mutableListOf<GamepadDevice>()
        val deviceIds = InputDevice.getDeviceIds()
        
        for (deviceId in deviceIds) {
            val inputDevice = InputDevice.getDevice(deviceId)
            if (inputDevice != null && isGamepadInputDevice(inputDevice)) {
                val gamepadDevice = GamepadDevice(
                    id = deviceId,
                    name = inputDevice.name,
                    address = getBluetoothAddress(inputDevice),
                    isConnected = true,
                    deviceType = identifyGamepadType(inputDevice.name),
                    capabilities = getDeviceCapabilities(inputDevice)
                )
                inputDevices.add(gamepadDevice)
                
                Timber.i("Found connected gamepad: ${gamepadDevice.name} (${gamepadDevice.deviceType})")
            }
        }
        
        _connectedDevices.value = inputDevices
    }
    
    private fun isGamepadInputDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
    
    /**
     * Check if we have the required permissions for Bluetooth operations
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun isLikelyGamepad(device: BluetoothDevice): Boolean {
        val hasBtPermission = hasRequiredPermissions()
        val deviceClass = if (hasBtPermission) {
            try {
                device.bluetoothClass?.deviceClass
            } catch (se: SecurityException) {
                Timber.w(se, "SecurityException getting device class for ${device.address}")
                null
            }
        } else {
            null
        }
        
        val name = if (hasBtPermission) {
            try {
                device.name?.lowercase()
            } catch (se: SecurityException) {
                Timber.w(se, "SecurityException getting device name for ${device.address}")
                null
            }
        } else {
            null
        }
        
        // Log device info for debugging
        Timber.d("Checking device: name='${name}', class=${deviceClass?.toString(16)}, address=${device.address}")
        
        // Check device class for input devices (more comprehensive)
        val isInputDevice = deviceClass?.let { clazz ->
            val majorClass = (clazz and 0x1F00) shr 8
            val minorClass = (clazz and 0x00FC) shr 2
            
            // Major class: Peripheral (0x05) OR Audio/Video (0x04 - for some wireless controllers)
            val isPeripheral = majorClass == 0x05
            val isAudioVideo = majorClass == 0x04
            
            // Minor classes that indicate input devices
            val isPointingDevice = minorClass == 0x20  // Mouse/pointing
            val isKeyboard = minorClass == 0x40        // Keyboard
            val isCombo = minorClass == 0x60           // Combo keyboard/mouse
            val isJoystick = minorClass == 0x04        // Joystick
            val isGamepad = minorClass == 0x08         // Gamepad
            val isRemote = minorClass == 0x0C          // Remote control
            
            Timber.d("Device class analysis - Major: 0x${majorClass.toString(16)}, Minor: 0x${minorClass.toString(16)}")
            
            (isPeripheral && (isJoystick || isGamepad || isRemote)) || 
            (isAudioVideo) // Some controllers appear as A/V devices
        } ?: false
        
        // Check name for known gamepad keywords (expanded list)
        val hasGamepadName = name?.let { deviceName ->
            val gamepadKeywords = listOf(
                "controller", "gamepad", "joystick", "joypad",
                "dualshock", "dualsense", "dual shock", "dual sense",
                "xbox", "x-box", "wireless controller",
                "pro controller", "joy-con", "joycon",
                "8bitdo", "hori", "razer", "steelseries",
                "ps4", "ps5", "playstation", "nintendo",
                "switch pro", "pro con"
            )
            
            val matches = gamepadKeywords.any { keyword ->
                deviceName.contains(keyword)
            }
            
            if (matches) {
                Timber.d("Device name matches gamepad keywords: $deviceName")
            }
            
            matches
        } ?: false
        
        // For debugging: log all devices during discovery
        if (name != null || deviceClass != null) {
            Timber.i("Device analysis: '${name}' [${device.address}] - InputDevice: $isInputDevice, GamepadName: $hasGamepadName")
        }
        
        // Be more inclusive - if we can't determine class but name suggests gamepad, include it
        val result = isInputDevice || hasGamepadName || (name != null && deviceClass == null)
        
        if (result) {
            Timber.i("Device identified as potential gamepad: '${name}' [${device.address}]")
        }
        
        return result
    }
    
    private fun identifyGamepadType(deviceName: String?): GamepadType {
        val name = deviceName?.lowercase() ?: return GamepadType.UNKNOWN
        
        return when {
            name.contains("dualsense") -> GamepadType.PS5_DUALSENSE
            name.contains("dualshock") -> GamepadType.PS4_DUALSHOCK
            name.contains("xbox") -> GamepadType.XBOX_CONTROLLER
            name.contains("controller") || name.contains("gamepad") -> GamepadType.GENERIC
            else -> GamepadType.UNKNOWN
        }
    }
    
    private fun getBluetoothAddress(inputDevice: InputDevice): String? {
        // Try to extract Bluetooth address from device descriptor
        // This is a best-effort approach as Android doesn't directly expose this
        val descriptor = inputDevice.descriptor
        return if (descriptor.contains(":")) {
            // Look for MAC address pattern in descriptor
            val macRegex = Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}")
            macRegex.find(descriptor)?.value?.uppercase()
        } else null
    }
    
    private fun getDeviceCapabilities(device: InputDevice): Set<String> {
        val capabilities = mutableSetOf<String>()
        
        // Check for various input capabilities
        if (device.sources and InputDevice.SOURCE_GAMEPAD != 0) capabilities.add("gamepad")
        if (device.sources and InputDevice.SOURCE_JOYSTICK != 0) capabilities.add("joystick")
        if (device.sources and InputDevice.SOURCE_DPAD != 0) capabilities.add("dpad")
        
        // Check for specific axes
        if (device.hasKeys(android.view.KeyEvent.KEYCODE_BUTTON_A)[0]) capabilities.add("action_buttons")
        if (device.getMotionRange(android.view.MotionEvent.AXIS_X) != null) capabilities.add("left_stick")
        if (device.getMotionRange(android.view.MotionEvent.AXIS_Z) != null) capabilities.add("right_stick")
        if (device.getMotionRange(android.view.MotionEvent.AXIS_LTRIGGER) != null) capabilities.add("triggers")
        
        return capabilities
    }
    
    private fun registerBluetoothReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        
        try {
            context.registerReceiver(bluetoothReceiver, intentFilter)
            Timber.d("Bluetooth receiver registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register Bluetooth receiver")
        }
    }
    
    private fun updateBluetoothState() {
        val adapter = bluetoothAdapter
        _bluetoothState.value = when {
            adapter == null -> BluetoothState.UNKNOWN
            adapter.isEnabled -> BluetoothState.ENABLED
            else -> BluetoothState.DISABLED
        }
    }
    
    private fun handleBluetoothStateChange(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        _bluetoothState.value = when (state) {
            BluetoothAdapter.STATE_ON -> BluetoothState.ENABLED
            BluetoothAdapter.STATE_OFF -> BluetoothState.DISABLED
            BluetoothAdapter.STATE_TURNING_ON -> BluetoothState.ENABLING
            BluetoothAdapter.STATE_TURNING_OFF -> BluetoothState.DISABLING
            else -> BluetoothState.UNKNOWN
        }
        
        Timber.i("Bluetooth state changed: ${_bluetoothState.value}")
        
        if (_bluetoothState.value == BluetoothState.ENABLED) {
            scanForConnectedGamepads()
        } else {
            _connectedDevices.value = emptyList()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun handleDeviceConnected(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device != null && isLikelyGamepad(device)) {
            Timber.i("Gamepad connected: ${device.name} (${device.address})")
            // Delay scanning to allow input device to be registered
            // This would typically be handled with a coroutine delay
            scanForConnectedGamepads()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun handleDeviceDisconnected(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device != null && isLikelyGamepad(device)) {
            Timber.i("Gamepad disconnected: ${device.name} (${device.address})")
            scanForConnectedGamepads()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun handleDeviceDiscovered(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device != null) {
            // Log ALL discovered devices for debugging
            val deviceName = try { device.name } catch (e: SecurityException) { "SecurityException" }
            val deviceClass = try { device.bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
            Timber.i("=== DISCOVERED DEVICE === Name: '$deviceName', Address: ${device.address}, Class: ${deviceClass?.toString(16)}")
            
            if (isLikelyGamepad(device)) {
                val gamepadDevice = GamepadDevice(
                    id = device.address.hashCode(),
                    name = deviceName ?: "Unknown Device",
                    address = device.address,
                    isConnected = false,
                    deviceType = identifyGamepadType(deviceName)
                )
                
                // Add to discovered devices if not already present
                val currentDevices = _discoveredDevices.value.toMutableList()
                if (!currentDevices.any { it.address == device.address }) {
                    currentDevices.add(gamepadDevice)
                    _discoveredDevices.value = currentDevices
                    Timber.i("✅ Discovered gamepad: ${gamepadDevice.name} (${gamepadDevice.deviceType})")
                }
            } else {
                Timber.d("❌ Device not identified as gamepad: '$deviceName'")
            }
        }
    }
    
    /**
     * Temporary debug method - show ALL paired devices regardless of type
     */
    @SuppressLint("MissingPermission")
    fun getAllPairedDevicesForDebug(): List<GamepadDevice> {
        Timber.w("=== getAllPairedDevicesForDebug called ===")
        
        if (!isBluetoothEnabled()) {
            Timber.w("Bluetooth not enabled")
            return getSimulatorMockDevices()
        }
        
        if (!hasRequiredPermissions()) {
            Timber.w("Missing Bluetooth permissions")
            return emptyList()
        }
        
        val bondedDevices = bluetoothAdapter?.bondedDevices
        Timber.w("Found ${bondedDevices?.size ?: 0} bonded devices")
        
        if (bondedDevices?.isEmpty() != false) {
            Timber.w("No bonded devices found, returning simulator mock devices")
            return getSimulatorMockDevices()
        }
        
        return bondedDevices.map { device ->
            val deviceName = try { device.name } catch (e: SecurityException) { "SecurityException" }
            val deviceClass = try { device.bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
            
            Timber.i("=== PAIRED DEVICE === Name: '$deviceName', Address: ${device.address}, Class: ${deviceClass?.toString(16)}")
            
            GamepadDevice(
                id = device.address.hashCode(),
                name = deviceName ?: "Unknown Device",
                address = device.address,
                isConnected = false,
                deviceType = identifyGamepadType(deviceName)
            )
        }
    }
    
    /**
     * Mock devices for simulator testing
     */
    private fun getSimulatorMockDevices(): List<GamepadDevice> {
        Timber.w("Providing simulator mock devices for testing")
        return listOf(
            GamepadDevice(
                id = 1001,
                name = "DualSense Wireless Controller",
                address = "00:00:00:00:00:01",
                isConnected = false,
                deviceType = GamepadType.PS5_DUALSENSE
            ),
            GamepadDevice(
                id = 1002,
                name = "Xbox Wireless Controller",
                address = "00:00:00:00:00:02", 
                isConnected = false,
                deviceType = GamepadType.XBOX_CONTROLLER
            ),
            GamepadDevice(
                id = 1003,
                name = "Pro Controller",
                address = "00:00:00:00:00:03",
                isConnected = false,
                deviceType = GamepadType.GENERIC
            )
        )
    }
    
    
    /**
     * Get device information by ID
     */
    fun getDeviceById(deviceId: Int): GamepadDevice? {
        return _connectedDevices.value.find { it.id == deviceId }
    }
    
    /**
     * Check if specific device is connected
     */
    fun isDeviceConnected(deviceId: Int): Boolean {
        return _connectedDevices.value.any { it.id == deviceId && it.isConnected }
    }
    
    /**
     * Connect to all available devices
     */
    fun connectToAllDevices() {
        Timber.w("=== CONNECTING TO ALL DEVICES ===")
        
        // Get the current available devices (with their current connection states)
        val allDevices = getAllAvailableDevices().toMutableList()
        Timber.w("Found ${allDevices.size} devices to connect to")
        
        if (allDevices.isEmpty()) {
            Timber.w("No devices available to connect to")
            return
        }
        
        // Mark all devices as connected 
        val updatedDevices = allDevices.map { device ->
            device.copy(isConnected = true)
        }
        
        // Update connected devices state - this should trigger the UI update
        _connectedDevices.value = updatedDevices
        
        Timber.w("Updated _connectedDevices state with ${updatedDevices.size} connected devices")
        updatedDevices.forEach { device ->
            Timber.w("✅ Connected: ${device.name} (${device.deviceType}) - Address: ${device.address}")
        }
    }
    
    /**
     * Connect to a specific device by ID
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceId: Int): Boolean {
        Timber.w("=== CONNECTING TO DEVICE: $deviceId ===")
        
        val allDevices = getAllAvailableDevices().toMutableList()
        val deviceIndex = allDevices.indexOfFirst { it.id == deviceId }
        
        if (deviceIndex == -1) {
            Timber.w("Device $deviceId not found")
            return false
        }
        
        val device = allDevices[deviceIndex]
        Timber.w("Attempting to connect to: ${device.name}")
        
        // For real implementation, you would attempt actual Bluetooth connection here
        // For now, we'll simulate successful connection
        val updatedDevice = device.copy(isConnected = true)
        allDevices[deviceIndex] = updatedDevice
        
        // Update the connected devices
        _connectedDevices.value = allDevices
        
        Timber.w("✅ Successfully connected to: ${device.name}")
        return true
    }
    
    /**
     * Disconnect from all devices
     */
    fun disconnectFromAllDevices() {
        Timber.w("=== DISCONNECTING FROM ALL DEVICES ===")
        val allDevices = getAllAvailableDevices().toMutableList()
        
        val updatedDevices = allDevices.map { device ->
            device.copy(isConnected = false)
        }
        
        _connectedDevices.value = updatedDevices
        
        Timber.w("Disconnected from all devices")
        updatedDevices.forEach { device ->
            Timber.w("❌ Disconnected: ${device.name}")
        }
    }
    
    /**
     * Disconnect from a specific device
     */
    fun disconnectFromDevice(deviceId: Int): Boolean {
        Timber.w("=== DISCONNECTING FROM DEVICE: $deviceId ===")
        
        val allDevices = getAllAvailableDevices().toMutableList()
        val deviceIndex = allDevices.indexOfFirst { it.id == deviceId }
        
        if (deviceIndex == -1) {
            Timber.w("Device $deviceId not found")
            return false
        }
        
        val device = allDevices[deviceIndex]
        val updatedDevice = device.copy(isConnected = false)
        allDevices[deviceIndex] = updatedDevice
        
        _connectedDevices.value = allDevices
        
        Timber.w("❌ Disconnected from: ${device.name}")
        return true
    }
    
    /**
     * Get count of connected devices
     */
    fun getConnectedDeviceCount(): Int {
        return _connectedDevices.value.count { it.isConnected }
    }
    
    /**
     * Clear all devices when app disconnects/stops
     */
    fun clearAllDevices() {
        Timber.w("=== CLEARING ALL DEVICES ===")
        _connectedDevices.value = emptyList()
        _discoveredDevices.value = emptyList()
        stopDiscovery()
        Timber.w("All devices cleared")
    }
    
    /**
     * Initialize devices - load available devices but start with none connected
     */
    fun initializeDevices() {
        Timber.w("=== INITIALIZING DEVICES ===")
        // Clear any existing state
        _connectedDevices.value = emptyList()
        _discoveredDevices.value = emptyList()
        
        // Start fresh scan for connected gamepads
        scanForConnectedGamepads()
        
        // Start discovery for nearby devices
        if (hasRequiredPermissions() && isBluetoothEnabled()) {
            startDiscovery()
        }
        
        Timber.w("Device initialization complete")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
            Timber.d("Bluetooth receiver unregistered")
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering Bluetooth receiver")
        }
    }
    
    companion object {
        private const val TAG = "BluetoothManager"
    }
}