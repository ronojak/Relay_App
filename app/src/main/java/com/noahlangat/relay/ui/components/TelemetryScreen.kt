package com.noahlangat.relay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.noahlangat.relay.telemetry.FrameDirection
import com.noahlangat.relay.telemetry.FrameEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryScreen(
    enabled: Boolean,
    events: List<FrameEvent>,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Telemetry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClear, enabled = events.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Capture comms", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Records incoming input and outgoing frames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            HorizontalDivider()

            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (enabled) "Waiting for traffic…" else "Capture is off. Toggle it on to record frames.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Newest first.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        FrameRow(event, timeFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameRow(event: FrameEvent, timeFormat: SimpleDateFormat) {
    val outgoing = event.direction == FrameDirection.OUTGOING
    val accent = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val tag = when (event.direction) {
        FrameDirection.OUTGOING -> "OUT ▶"
        FrameDirection.INCOMING_INPUT -> "◀ IN"
        FrameDirection.INCOMING_RETURN -> "◀ RET"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tag, style = MaterialTheme.typography.labelMedium, color = accent)
                Text(
                    timeFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append(event.label)
                event.seq?.let { append(" • seq $it") }
                if (event.sizeBytes > 0) append(" • ${event.sizeBytes}B")
            }
            Text(meta, style = MaterialTheme.typography.bodySmall)
            Text(event.summary, style = MaterialTheme.typography.bodyMedium)
            if (event.hexPreview.isNotEmpty()) {
                Text(
                    event.hexPreview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
