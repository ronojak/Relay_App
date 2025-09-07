package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.R

@Composable
fun StatsPanel(
    packetsTransmitted: Long,
    packetsDropped: Long,
    currentHz: Float,
    averageLatency: Float,
    p99Latency: Float,
    uptime: String,
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
                text = stringResource(R.string.performance_stats),
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = stringResource(R.string.packets_transmitted),
                    value = packetsTransmitted.toString()
                )
                StatItem(
                    label = stringResource(R.string.packets_dropped),
                    value = packetsDropped.toString()
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = stringResource(R.string.current_rate),
                    value = stringResource(R.string.hz_format, currentHz)
                )
                StatItem(
                    label = stringResource(R.string.uptime),
                    value = uptime
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = stringResource(R.string.average_latency),
                    value = stringResource(R.string.ms_format, averageLatency)
                )
                StatItem(
                    label = stringResource(R.string.p99_latency),
                    value = stringResource(R.string.ms_format, p99Latency)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall
        )
    }
}