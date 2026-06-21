package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.data.SinkType
import com.noahlangat.relay.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sinkType: SinkType,
    primaryHost: String,
    primaryPort: Int,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onApply: (sinkType: SinkType, host: String, port: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var type by remember(sinkType) { mutableStateOf(sinkType) }
    var hostText by remember(primaryHost) { mutableStateOf(primaryHost) }
    var portText by remember(primaryPort) { mutableStateOf(primaryPort.toString()) }
    var applied by remember { mutableStateOf(false) }

    val isWifi = type == SinkType.WIFI_CLIENT || type == SinkType.WIFI_SERVER
    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val hostValid = type != SinkType.WIFI_CLIENT || hostText.isNotBlank()
    val canApply = isWifi && portValid && hostValid

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Primary Connection", style = MaterialTheme.typography.titleLarge)

        Text(
            text = "The peripheral (MCU) is the server. Use WiFi · dial out to test against " +
                "relay_test_server.py --role sink at its IP:port.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Sink type selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == SinkType.WIFI_CLIENT,
                onClick = { type = SinkType.WIFI_CLIENT },
                label = { Text(SinkType.WIFI_CLIENT.displayName) }
            )
            FilterChip(
                selected = type == SinkType.WIFI_SERVER,
                onClick = { type = SinkType.WIFI_SERVER },
                label = { Text(SinkType.WIFI_SERVER.displayName) }
            )
            FilterChip(
                selected = type == SinkType.BLUETOOTH,
                onClick = { /* Phase 4 */ },
                enabled = false,
                label = { Text("Bluetooth · soon") }
            )
        }

        // Host (IP) — only relevant when dialing out
        OutlinedTextField(
            value = hostText,
            onValueChange = { hostText = it.trim() },
            label = { Text("Peripheral IP / host") },
            singleLine = true,
            enabled = type == SinkType.WIFI_CLIENT,
            isError = type == SinkType.WIFI_CLIENT && hostText.isBlank(),
            supportingText = {
                Text(
                    if (type == SinkType.WIFI_CLIENT) "e.g. 192.168.1.100 (the PC running the test server)"
                    else "Not used when the phone listens (the peripheral dials in)."
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Port
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(if (type == SinkType.WIFI_CLIENT) "Peripheral port" else "Listening port") },
            singleLine = true,
            isError = !portValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("1–65535. Changes restart the relay immediately.") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                onApply(type, hostText.trim(), portValue ?: primaryPort)
                applied = true
            },
            enabled = canApply
        ) {
            Text("Apply & Restart Relay")
        }

        if (applied) {
            val target = if (type == SinkType.WIFI_CLIENT) "$hostText:$portText" else "port $portText"
            Snackbar { Text("Relay restarting • $target") }
        }

        HorizontalDivider()

        Text(text = "Appearance", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeChange(ThemeMode.SYSTEM) },
                label = { Text("System") }
            )
            FilterChip(
                selected = themeMode == ThemeMode.LIGHT,
                onClick = { onThemeChange(ThemeMode.LIGHT) },
                label = { Text("Light") }
            )
            FilterChip(
                selected = themeMode == ThemeMode.DARK,
                onClick = { onThemeChange(ThemeMode.DARK) },
                label = { Text("Dark") }
            )
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, colors = ButtonDefaults.textButtonColors()) {
            Text("Back")
        }
    }
}
