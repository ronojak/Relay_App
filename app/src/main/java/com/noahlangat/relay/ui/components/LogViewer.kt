package com.noahlangat.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LogViewer(
    logMessages: List<LogMessage>,
    modifier: Modifier = Modifier,
    onClearLogs: () -> Unit = {}
) {
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }

    val filteredMessages = logMessages.filter { message ->
        val levelMatch = selectedLogLevel == null || message.level == selectedLogLevel
        val deviceMatch = selectedDevice == null || message.getDeviceLabel() == selectedDevice
        levelMatch && deviceMatch
    }

    val uniqueDevices = logMessages.map { it.getDeviceLabel() }.distinct().sorted()

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Messages",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredMessages.size} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear logs",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogLevelFilter(
                    selectedLevel = selectedLogLevel,
                    onLevelSelected = { selectedLogLevel = it },
                    modifier = Modifier.weight(1f)
                )
                DeviceFilter(
                    selectedDevice = selectedDevice,
                    devices = uniqueDevices,
                    onDeviceSelected = { selectedDevice = it },
                    modifier = Modifier.weight(1f)
                )
            }

            val listState = rememberLazyListState()

            LaunchedEffect(logMessages.size) {
                if (logMessages.isNotEmpty()) {
                    listState.animateScrollToItem(logMessages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (filteredMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages to display",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(filteredMessages) { message ->
                        LogMessageItem(message = message)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLevelColor(level: LogLevel): Color {
    return when (level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN  -> Color(0xFFF57C00) // Orange
        LogLevel.INFO  -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> Color(0xFF607D8B) // Blue-grey
    }
}
@Composable
private fun LogMessageItem(
    message: LogMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
                .fillMaxWidth()
            .background(
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp)
        )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(LogLevelColor(message.level))
        )
        Text(
            text = message.getAbbreviatedDevice(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(30.dp),
            maxLines = 1
        )
        Text(
            text = message.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
            )
        Text(
            text = message.getFormattedTimestamp(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
                )
            }
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLevelFilter(
    selectedLevel: LogLevel?,
    onLevelSelected: (LogLevel?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLevel?.name ?: "All Levels",
            onValueChange = { },
            readOnly = true,
            label = { Text("Level") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All Levels") },
                onClick = {
                    onLevelSelected(null)
                    expanded = false
}
            )
            LogLevel.values().forEach { level ->
                DropdownMenuItem(
                    text = { Text(level.name) },
                    onClick = {
                        onLevelSelected(level)
                        expanded = false
            }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceFilter(
    selectedDevice: String?,
    devices: List<String>,
    onDeviceSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedDevice ?: "All Devices",
            onValueChange = { },
            readOnly = true,
            label = { Text("Device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All Devices") },
                onClick = {
                    onDeviceSelected(null)
                    expanded = false
                }
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device) },
                    onClick = {
                        onDeviceSelected(device)
                        expanded = false
                    }
                )
            }
        }
    }
}

