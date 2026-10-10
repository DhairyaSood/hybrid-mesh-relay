package com.hybridmesh.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MessageTraceEventEntity
import com.hybridmesh.relay.messaging.mesh.MeshTransportKind
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.ui.components.NodeIdText
import java.text.DateFormat
import java.util.Date
import android.widget.Toast

@Composable
fun MessageDiagnosticsDialog(
    message: MessageRecordEntity,
    events: List<MessageTraceEventEntity>,
    localNodeId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isOutgoing = message.senderNodeId.equals(localNodeId, ignoreCase = true)
    val routeCodes = message.routeTrace.orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    val routeNames = routeCodes.mapNotNull(MeshTransportKind::fromTraceCode).map {
        when (it) {
            MeshTransportKind.BLE_GATT -> "BLE"
            MeshTransportKind.WIFI_DIRECT -> "Wi-Fi Direct"
            MeshTransportKind.LORA -> "LoRa (reserved)"
        }
    }
    val statusLabel = when (message.status) {
        DeliveryStatus.DELIVERED.name -> "Delivered"
        DeliveryStatus.IN_FLIGHT.name -> "Sending"
        DeliveryStatus.QUEUED.name -> "Queued"
        DeliveryStatus.FAILED.name -> "Failed"
        else -> message.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
    val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(message.createdAt))
    val delivered = message.deliveredAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(it)) }
    // Outgoing createdAt and deliveredAt are measured on this same device. For
    // incoming messages, createdAt belongs to the remote sender's clock, so a
    // duration would be misleading unless clocks were synchronized.
    val elapsed = if (isOutgoing) message.deliveredAt?.let { (it - message.createdAt).takeIf { delta -> delta >= 0L } } else null
    val elapsedLabel = elapsed?.let { "$it ms" } ?: if (isOutgoing) "Unavailable" else "Unavailable (remote clock not comparable)"
    val report = buildString {
        appendLine("Neyra message diagnostics")
        appendLine("Message ID: ${message.messageId}")
        appendLine("Status: $statusLabel")
        appendLine("Created: $created")
        appendLine("Delivered: ${delivered ?: "Not confirmed"}")
        appendLine("Observed duration: $elapsedLabel")
        appendLine("Last recorded transport: ${message.lastTransport ?: "Unknown"}")
        appendLine("Route trace: ${routeNames.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "Unavailable"}")
        appendLine("Relay hops: ${message.deliveryHopCount?.toString() ?: "Unknown"}")
        appendLine("Attempts: ${message.attemptCount}")
        appendLine("Last error: ${message.lastError ?: "None recorded"}")
        appendLine("Events:")
        events.forEach { event ->
            appendLine("${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.occurredAt))} ${event.eventType} transport=${event.transport ?: "-"} peer=${event.peerNodeId ?: "-"} result=${event.resultCode ?: "-"} duration=${event.durationMs?.let { "${it}ms" } ?: "-"}")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Message diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("NEYRA · MESSAGE REPORT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close diagnostics")
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(statusLabel.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                isOutgoing && message.status == DeliveryStatus.DELIVERED.name -> "Destination delivery acknowledged"
                                !isOutgoing && message.status == DeliveryStatus.DELIVERED.name -> "Message received on this device"
                                else -> "Delivery has not been confirmed"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text("MESSAGE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                DiagnosticRow("Created", created)
                DiagnosticRow("Delivered", delivered ?: "Not confirmed")
                DiagnosticRow("Observed duration", elapsedLabel)
                DiagnosticRow("Last recorded transport", when (message.lastTransport) { "BLE_MESH" -> "Bluetooth Low Energy"; "WIFI_DIRECT" -> "Wi-Fi Direct"; else -> message.lastTransport ?: "Unknown" })
                DiagnosticRow("Relay hops", message.deliveryHopCount?.toString() ?: "Unknown")
                DiagnosticRow("Route trace", when { message.routeTrace.isNullOrBlank() -> "Unavailable"; message.routeTraceComplete -> "Complete"; else -> "Partial" })
                DiagnosticRow("Recorded attempts", message.attemptCount.toString())
                Divider()
                Text("DELIVERY ROUTE · ${when { routeCodes.isEmpty() -> "UNAVAILABLE"; message.routeTraceComplete -> "COMPLETE TRACE"; else -> "PARTIAL TRACE" }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (routeNames.isEmpty()) {
                    Text("Route unavailable. This message may predate route tracing or have crossed a node that did not preserve trace data.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(if (isOutgoing) "Your device · origin" else "Origin device", fontWeight = FontWeight.SemiBold)
                    routeNames.forEachIndexed { index, transport ->
                        Text("↓  Link ${index + 1}: $transport", style = MaterialTheme.typography.bodyMedium)
                        if (index < routeNames.lastIndex) Text("      Relay (identity not carried in this trace)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(if (isOutgoing) "Destination device" else "Your device · destination", fontWeight = FontWeight.SemiBold)
                }
                Divider()
                Text("TRANSMISSION TIMELINE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (events.isEmpty()) {
                    Text("No detailed local events recorded yet. Relay event histories remain on the relay devices.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    events.forEach { event ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(event.eventType.replace('_', ' '), fontWeight = FontWeight.SemiBold)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(event.occurredAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val details = listOfNotNull(event.transport, event.peerNodeId?.let { "peer $it" }, event.resultCode, event.durationMs?.let { "${it} ms" }, event.detail).joinToString(" · ")
                            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Divider()
                Text("TECHNICAL DETAILS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                DiagnosticRow("Message ID", message.messageId)
                DiagnosticRow("Source node", message.senderNodeId) {
                    clipboard.setText(AnnotatedString(message.senderNodeId))
                    Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
                }
                DiagnosticRow("Destination node", message.recipientNodeId) {
                    clipboard.setText(AnnotatedString(message.recipientNodeId))
                    Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
                }
                DiagnosticRow("Last error", message.lastError ?: "None recorded")
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("COPY REPORT") }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DiagnosticRow(label: String, value: String, onValueClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onValueClick != null) {
            NodeIdText(value, modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, maxLines = 3)
        } else {
            Text(value, modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
