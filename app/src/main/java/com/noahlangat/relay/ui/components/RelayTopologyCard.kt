package com.noahlangat.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Headline relay view: input source → phone → output sink. Each role is a tonal
 * node chip (tinted when active), connected by chevrons that light up when data
 * can flow.
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
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Relay")

            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
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
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.medium,
        color = container
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Arrow(active: Boolean) {
    Text(
        text = "›",
        style = MaterialTheme.typography.titleLarge,
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
