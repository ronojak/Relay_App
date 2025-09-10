package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    currentPort: String,
    onPortChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var portText by remember { mutableStateOf(currentPort) }
    var showSnackbar by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "Advanced Settings", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() } },
            label = { Text("TCP Server Port") },
            singleLine = true,
            supportingText = { Text("Change the listening port for the TCP server. Changes take effect immediately.") }
        )
        Button(onClick = {
            onPortChange(portText)
            showSnackbar = true
        }) {
            Text("Apply and Restart Server")
        }
        if (showSnackbar) {
            Snackbar {
                Text("Server restarted on port $portText")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, colors=ButtonDefaults.textButtonColors()) {
            Text("Back")
        }
    }
}
