package com.noahlangat.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LogViewer(
    logMessages: List<LogMessage>,
    modifier: Modifier = Modifier,
    onClearLogs: () -> Unit = {},
    onExpand: () -> Unit = {}
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredMessages.size}",
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
                    IconButton(
                        onClick = onExpand,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Full screen",
                            modifier = Modifier.size(20.dp)
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
    modifier: Modifier = Modifier,
    expanded: Boolean = false
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
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
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

/** Full-screen, untruncated view of the device messages with filters and copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logMessages: List<LogMessage>,
    onClearLogs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val filteredMessages = logMessages.filter { message ->
        val levelMatch = selectedLogLevel == null || message.level == selectedLogLevel
        val deviceMatch = selectedDevice == null || message.getDeviceLabel() == selectedDevice
        levelMatch && deviceMatch
    }
    val uniqueDevices = logMessages.map { it.getDeviceLabel() }.distinct().sorted()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Device Messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val text = filteredMessages.joinToString("\n") {
                                "${it.getFormattedTimestamp()} ${it.getDeviceLabel()} ${it.level} ${it.message}"
                            }
                            clipboard.setText(AnnotatedString(text))
                        },
                        enabled = filteredMessages.isNotEmpty()
                    ) { Text("Copy") }
                    IconButton(onClick = onClearLogs, enabled = logMessages.isNotEmpty()) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
            LaunchedEffect(filteredMessages.size) {
                if (filteredMessages.isNotEmpty()) {
                    listState.animateScrollToItem(filteredMessages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filteredMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
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
                        LogMessageItem(message = message, expanded = true)
                    }
                }
            }
        }
    }
}

