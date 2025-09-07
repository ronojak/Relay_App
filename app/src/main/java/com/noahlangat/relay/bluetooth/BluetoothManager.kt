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
    
    private fun isLikelyGamepad(device: BluetoothDevice): Boolean {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val deviceClass = if (hasBtPermission) {
        try {
            device.bluetoothClass?.deviceClass
        } catch (se: SecurityException) {
            null
        }
    } else {
        null
    }
        val name = device.name?.lowercase()
        
        // Check device class for input devices
        val isInputDevice = deviceClass?.let { clazz ->
            // Major class: Peripheral (input devices)
            (clazz and 0x1F00) == 0x0500
        } ?: false
        
        // Check name for known gamepad keywords
        val hasGamepadName = name?.let { deviceName ->
            listOf("controller", "gamepad", "joystick", "dualshock", "dualsense", "xbox").any {
                deviceName.contains(it)
            }
        } ?: false
        
        return isInputDevice || hasGamepadName
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