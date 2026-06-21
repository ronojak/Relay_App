package com.noahlangat.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Headline relay view: input source → phone → output sink, each node showing its
 * label, detail and a live status dot. Replaces the old flat connection form with
 * a directional picture of what the relay is doing.
 */
@Composable
fun RelayTopologyCard(
    isRunning: Boolean,
    currentHz: Float,
    packets: Long,
    sourceLabel: String,
    sourceDetail: String,
    sourceActive: Boolean,
    sinkLabel: String,
    sinkDetail: String,
    sinkActive: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Relay", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Node(
                    role = "INPUT",
                    label = sourceLabel,
                    detail = sourceDetail,
                    active = sourceActive,
                    modifier = Modifier.weight(1f)
                )
                Arrow(active = isRunning && sourceActive)
                Node(
                    role = "RELAY",
                    label = if (isRunning) "${currentHz.toInt()} Hz" else "Idle",
                    detail = if (isRunning) "$packets pkts" else "stopped",
                    active = isRunning,
                    modifier = Modifier.weight(1f)
                )
                Arrow(active = isRunning && sinkActive)
                Node(
                    role = "OUTPUT",
                    label = sinkLabel,
                    detail = sinkDetail,
                    active = sinkActive,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun Node(
    role: String,
    label: String,
    detail: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Arrow(active: Boolean) {
    Text(
        text = "▶",
        style = MaterialTheme.typography.titleMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}
