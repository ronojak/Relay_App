package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.R

@Composable
fun QuickActionsPanel(
    onRestartService: () -> Unit,
    onNetworkDiagnostics: () -> Unit,
    onViewProtocolDocs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_actions),
                style = MaterialTheme.typography.titleMedium
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRestartService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.restart_service))
                }
                
                OutlinedButton(
                    onClick = onNetworkDiagnostics,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.network_diagnostics))
                }
                
                OutlinedButton(
                    onClick = onViewProtocolDocs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.protocol_docs))
                }
            }
        }
    }
}