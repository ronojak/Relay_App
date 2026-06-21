package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.R
import com.noahlangat.relay.bluetooth.BluetoothManager

/**
 * Controller (input) selection: pick a Bluetooth gamepad and connect/disconnect it.
 * Network/primary configuration lives in Settings; this panel is input-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    connectedDevices: List<BluetoothManager.GamepadDevice>,
    isDiscovering: Boolean = false,
    onDeviceSelect: (Int) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit = {},
    selectedDeviceId: Int? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Controller", style = MaterialTheme.typography.titleMedium)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh devices",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            var expanded by remember { mutableStateOf(false) }
            val selectedDevice = connectedDevices.find { it.id == selectedDeviceId }

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
                    if (connectedDevices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No devices found") },
                            onClick = { },
                            enabled = false
                        )
                    } else {
                        connectedDevices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(device.name)
                                        Text(
                                            text = "${device.deviceType.name} ${if (device.isConnected) "• Connected" else "• Available"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onDeviceSelect(device.id)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    enabled = selectedDevice != null
                ) {
                    Text(if (selectedDevice?.isConnected == true) "Reconnect" else "Connect")
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                    enabled = selectedDevice?.isConnected == true
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}
