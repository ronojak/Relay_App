package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.data.PrimaryMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    primaryMode: PrimaryMode,
    primaryHost: String,
    primaryPort: Int,
    onApply: (mode: PrimaryMode, host: String, port: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember(primaryMode) { mutableStateOf(primaryMode) }
    var hostText by remember(primaryHost) { mutableStateOf(primaryHost) }
    var portText by remember(primaryPort) { mutableStateOf(primaryPort.toString()) }
    var applied by remember { mutableStateOf(false) }

    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val hostValid = mode == PrimaryMode.SERVER || hostText.isNotBlank()
    val canApply = portValid && hostValid

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Primary Connection", style = MaterialTheme.typography.titleLarge)

        Text(
            text = "The peripheral (MCU) is the server. In Client mode the phone dials out to " +
                "its IP:port. Use Client mode to test against relay_test_server.py --role sink.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Mode selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == PrimaryMode.CLIENT,
                onClick = { mode = PrimaryMode.CLIENT },
                label = { Text("Client (dial out)") }
            )
            FilterChip(
                selected = mode == PrimaryMode.SERVER,
                onClick = { mode = PrimaryMode.SERVER },
                label = { Text("Server (listen)") }
            )
        }

        // Host (IP) — only relevant when dialing out
        OutlinedTextField(
            value = hostText,
            onValueChange = { hostText = it.trim() },
            label = { Text("Peripheral IP / host") },
            singleLine = true,
            enabled = mode == PrimaryMode.CLIENT,
            isError = mode == PrimaryMode.CLIENT && hostText.isBlank(),
            supportingText = {
                Text(
                    if (mode == PrimaryMode.CLIENT) "e.g. 192.168.1.100 (the PC running the test server)"
                    else "Not used in Server mode (the phone listens on all interfaces)."
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Port
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(if (mode == PrimaryMode.CLIENT) "Peripheral port" else "Listening port") },
            singleLine = true,
            isError = !portValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("1–65535. Changes restart the relay immediately.") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                onApply(mode, hostText.trim(), portValue ?: primaryPort)
                applied = true
            },
            enabled = canApply
        ) {
            Text("Apply & Restart Relay")
        }

        if (applied) {
            val target = if (mode == PrimaryMode.CLIENT) "$hostText:$portText" else "port $portText"
            Snackbar { Text("Relay restarting • $target") }
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, colors = ButtonDefaults.textButtonColors()) {
            Text("Back")
        }
    }
}
