package com.noahlangat.relay.ui
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noahlangat.relay.R
import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import com.noahlangat.relay.service.RelayServiceConnection
import com.noahlangat.relay.ui.components.*
import com.noahlangat.relay.ui.theme.RelayAppTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject
import com.noahlangat.relay.service.RelayService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var bluetoothManager: BluetoothManager
    
    @Inject
    lateinit var gamepadInputHandler: GamepadInputHandler
    
    private lateinit var serviceConnection: RelayServiceConnection
    private val viewModel: MainViewModel by viewModels()
    
    // Permission handling
    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.onBluetoothPermissionGranted()
        } else {
            viewModel.onBluetoothPermissionDenied()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        serviceConnection = RelayServiceConnection(this)
 lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        // Service running state -> ViewModel
        launch {
            serviceConnection.serviceState.collect { state ->
                val running = (state == com.noahlangat.relay.service.RelayService.ServiceState.RUNNING)
                viewModel.setServiceRunning(running)
            }
        }

        // Service stats (client info and performance metrics) -> ViewModel
        launch {
            serviceConnection.serviceStats.collect { stats ->
                val info = if (stats != null && stats.networkClients > 0) {
                    "Client connected"
                } else {
                    null // shows "No client connected"
                }
                viewModel.setClientInfo(info)
                
                // Update performance metrics
                if (stats != null) {
                    viewModel.updatePerformanceStats(
                        packetsTransmitted = stats.packetsRelayed,
                        packetsDropped = stats.errorCount.toLong(),
                        currentHz = stats.currentHz,
                        averageLatency = stats.averageLatency,
                        uptime = formatUptime(System.currentTimeMillis() - stats.uptime)
                    )
                }
            }
        }
        
        // Log messages -> ViewModel
        launch {
            serviceConnection.logMessages.collect { logMessages ->
                viewModel.updateLogMessages(logMessages)
            }
        }

    }
}

 setContent {
    RelayAppTheme {
        MainScreen(
            viewModel = viewModel,
            onSettingsClick = { /* Settings navigation not implemented */ },
            onStartService = {
                Timber.i("MainActivity: Starting RelayService...")
                val intent = android.content.Intent(
                    this,
                    com.noahlangat.relay.service.RelayService::class.java
                ).apply {
                    action = com.noahlangat.relay.service.RelayService.ACTION_START_RELAY
                }
                startForegroundService(intent)
                Timber.i("MainActivity: startForegroundService called")
            },
            onStopService = {
                val intent = android.content.Intent(
                    this,
                    com.noahlangat.relay.service.RelayService::class.java
                ).apply {
                    action = com.noahlangat.relay.service.RelayService.ACTION_STOP_RELAY
                }
                startService(intent)
            }
        )
    }
}

        // Check and request permissions
        checkPermissions()
    }
    
    override fun onStart() {
        super.onStart()
        Timber.i("MainActivity.onStart() - Binding to service")
        val bindResult = serviceConnection.bind()
        Timber.i("Service bind result: $bindResult")
        // Initialize devices when app starts
        bluetoothManager.initializeDevices()
    }
    
    override fun onStop() {
        super.onStop()
        serviceConnection.unbind()
        // Clear devices when app goes to background
        bluetoothManager.clearAllDevices()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager.cleanup()
    }
    
    // Handle gamepad input events
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Log ALL key events first to debug
        Timber.i("🔑 KEY EVENT: action=${event.action}, keyCode=${event.keyCode}, source=0x${event.source.toString(16)}, deviceId=${event.deviceId}")
        
        if (event.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK) != 0) {
            Timber.i("🎮 GAMEPAD KEY: action=${event.action}, keyCode=${event.keyCode}, deviceId=${event.deviceId}")
        }
        val handled = gamepadInputHandler.handleKeyEvent(event, event.deviceId)
        return handled || super.dispatchKeyEvent(event)
    }
    
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK) != 0) {
            Timber.d("MainActivity: Gamepad motion event - deviceId=${event.deviceId}")
        }
        val handled = gamepadInputHandler.handleMotionEvent(event, event.deviceId)
        return handled || super.dispatchGenericMotionEvent(event)
    }
    
    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ Bluetooth permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            // Pre-Android 12 Bluetooth permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            // Location permission required for Bluetooth discovery on older versions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestBluetoothPermission.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.onBluetoothPermissionGranted()
        }
    }
    
    private fun formatUptime(uptimeMs: Long): String {
        if (uptimeMs <= 0) return "00:00:00"
        
        val seconds = uptimeMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSettingsClick: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip(
                            isActive = uiState.isServiceRunning,
                            currentHz = uiState.currentHz
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Panel
            ConnectionPanel(
                connectedDevices = uiState.connectedDevices,
                serverPort = uiState.serverPort,
                clientInfo = uiState.clientInfo,
                isDiscovering = uiState.isDiscovering,
                onDeviceSelect = { deviceId -> viewModel.selectDevice(deviceId) },
                onPortChange = { port -> viewModel.updatePort(port) },
                onConnect = { viewModel.connectToDevice() },
                onDisconnect = { viewModel.disconnectFromDevice() },
                onRefresh = { viewModel.refreshDevices() },
                onConnectAll = { viewModel.connectToAllDevices() },
                onDisconnectAll = { viewModel.disconnectFromAllDevices() },
                selectedDeviceId = uiState.selectedDeviceId
            )
            
            // Log Viewer
            LogViewer(
                logMessages = uiState.logMessages,
                onClearLogs = { viewModel.clearLogMessages() }
            )
            
            // Service Control
            ServiceControlPanel(
                isServiceRunning = uiState.isServiceRunning,
                onStartService = onStartService,
                onStopService = onStopService
            )
            
            // Quick Actions
            QuickActionsPanel(
                onRestartService = {
                    onStopService()
                    onStartService()
                },
                onNetworkDiagnostics = { /* Network diagnostics not implemented */ },
                onViewProtocolDocs = { /* Protocol docs not implemented */ }
            )
        }
    }
}

@Composable
fun StatusChip(
    isActive: Boolean,
    currentHz: Float
) {
    AssistChip(
        onClick = { },
        label = {
            Text(
                text = if (isActive) {
                    "${currentHz.toInt()} Hz"
                } else {
                    stringResource(R.string.status_inactive)
                }
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier.size(8.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ) {}
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    RelayAppTheme {
        // Preview would show mock data
    }
}
