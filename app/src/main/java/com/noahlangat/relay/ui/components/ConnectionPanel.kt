package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.R
import com.noahlangat.relay.bluetooth.BluetoothManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    connectedDevices: List<BluetoothManager.GamepadDevice>,
    serverPort: String,
    clientInfo: String?,
    onDeviceSelect: (Int) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium
            )
            
            // Bluetooth Device Selection
            var expanded by remember { mutableStateOf(false) }
            var selectedDevice by remember { mutableStateOf<BluetoothManager.GamepadDevice?>(null) }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedDevice?.name ?: stringResource(R.string.no_device_selected),
                    onValueChange = { },
                    readOnly = true,
                    label = { Text(stringResource(R.string.bluetooth_device_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    connectedDevices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.name) },
                            onClick = {
                                selectedDevice = device
                                onDeviceSelect(device.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            // Network Settings
            OutlinedTextField(
                value = serverPort,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.server_port)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            // Client Info
            Text(
                text = clientInfo ?: stringResource(R.string.no_client_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Connection Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    enabled = selectedDevice != null
                ) {
                    Text(stringResource(R.string.connect))
                }
                
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        }
    }
}