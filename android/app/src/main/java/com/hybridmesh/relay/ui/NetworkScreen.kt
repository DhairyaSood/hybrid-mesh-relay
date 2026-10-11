package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkDiagnosticEvent
import com.hybridmesh.relay.network.NetworkDiagnosticsSnapshot
import com.hybridmesh.relay.network.NetworkEventKind
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.network.percentile
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import java.util.Locale

private const val WINDOW_MS = 5 * 60 * 1_000L

@Composable
fun NetworkScreen() {
    val viewModel: MessagesViewModel = viewModel()
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val diagnostics by viewModel.networkDiagnostics.collectAsStateWithLifecycle()
    val messagingManager = MessagingManager.getInstance(androidx.compose.ui.platform.LocalContext.current)
    val transport by messagingManager.transport.collectAsStateWithLifecycle()
    val mesh by messagingManager.mesh.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("NETWORK", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Live mesh health and measured transport activity.",
                color = RelayTextMuted
            )
            Row(Modifier.padding(top = 8.dp)) {
                listOf("OVERVIEW", "ACTIVITY", "ADVANCED").forEachIndexed { index, label ->
                    TextButton(onClick = { selectedTab = index }) {
                        Text(label, color = if (selectedTab == index) RelayAccent else RelayTextMuted)
                    }
                }
            }
        }
        when (selectedTab) {
            0 -> Overview(state, mesh, meshPeers.size, diagnostics)
            1 -> Activity(diagnostics)
            else -> Advanced(state, transport, mesh, diagnostics)
        }
    }
}

@Composable
private fun Overview(
    state: NetworkState,
    mesh: MeshRuntimeSnapshot,
    nearbyNodeCount: Int,
    diagnostics: NetworkDiagnosticsSnapshot
) {
    val now = System.currentTimeMillis()
    val events = diagnostics.inWindow(now, WINDOW_MS)
    val requests = diagnostics.requests(now, WINDOW_MS)
    val successful = requests.count { it.kind == NetworkEventKind.REQUEST_SUCCEEDED }
    val failures = requests.count {
        it.kind == NetworkEventKind.REQUEST_FAILED || it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
    }
    val durations = requests.mapNotNull { it.durationMs }
    val active = activeRequestEvents(events).size
    val health = when {
        !mesh.running || mesh.availableTransports.isEmpty() -> "OFFLINE"
        failures > 0 || mesh.wifiDirectLastError != null -> "DEGRADED"
        else -> "HEALTHY"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("CONNECTION HEALTH") {
                MetricLine("STATE", health, "BLE ${state.bleRuntimeState.name} · mesh ${if (mesh.running) "running" else "stopped"}")
                MetricLine("WINDOW", "LAST 5 MIN", "measured transport events retained: ${events.size}")
            }
        }
        item {
            SectionCard("TRAFFIC · MEASURED") {
                MetricLine("ATTEMPTS", requests.size.toString(), "$successful accepted · $failures failed")
                MetricLine("SUCCESS RATE", if (requests.isEmpty()) "—" else "${(successful * 100.0 / requests.size).oneDecimal()}%", "accepted attempts / completed attempts")
                MetricLine("ACTIVE", active.coerceAtLeast(0).toString(), "transport attempts without a terminal result")
                MetricLine("RETRIES", requests.count { (it.attempt ?: 1) > 1 }.toString(), "attempts after the first candidate")
            }
        }
        item {
            SectionCard("PERFORMANCE · MEASURED") {
                MetricLine("MEDIAN LATENCY", percentile(durations, 0.5)?.let { "$it ms" } ?: "—", "${durations.size} completed samples")
                MetricLine("P95 LATENCY", percentile(durations, 0.95)?.let { "$it ms" } ?: "—", "upper-tail completed latency")
                MetricLine("BYTES ATTEMPTED", formatBytes(requests.sumOf { it.bytes }), "wire payload bytes supplied to transports")
            }
        }
        item {
            SectionCard("MESH RUNTIME · LIVE") {
                MetricLine("NEARBY NODES", nearbyNodeCount.toString(), "${state.phoneNodeCount} phones · ${state.relayNodeCount} relays")
                MetricLine("BLUETOOTH", bluetoothLabel(state.bluetoothState), "BLE capability ${if (state.bleSupported) "available" else "unsupported"}")
                MetricLine("INTERNET", if (state.internetAvailable) "AVAILABLE" else "OFFLINE", "informational; mesh messaging does not require internet")
                MetricLine("FORWARD QUEUE", mesh.pendingForwarding.toString(), "durable transit packets")
                MetricLine("AVAILABLE BEARERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" · "), "currently usable next hops")
                MetricLine("WI-FI PEERS", mesh.connectedWifiPeers.toString(), mesh.wifiDirectStage)
            }
        }
        if (failures > 0 || mesh.wifiDirectLastError != null) {
            item {
                SectionCard("NEEDS ATTENTION") {
                    if (failures > 0) MetricLine("FAILED ATTEMPTS", failures.toString(), "Open ACTIVITY and inspect the result field.")
                    mesh.wifiDirectLastError?.let { MetricLine("WI-FI DIRECT", "ERROR", it) }
                }
            }
        }
        item {
            Text(
                "Updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())}. Values marked measured come from real transport attempts; — means no sample exists.",
                color = RelayTextMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun Activity(diagnostics: NetworkDiagnosticsSnapshot) {
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<NetworkDiagnosticEvent?>(null) }
    val filters = listOf("ALL", "FAILURES", "SLOW", "ACTIVE", "RETRIES")
    val events = diagnostics.events.asReversed().filter {
        when (filter) {
            "FAILURES" -> it.kind == NetworkEventKind.REQUEST_FAILED || it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
            "SLOW" -> (it.durationMs ?: 0L) >= 1_000L
            "ACTIVE" -> it in activeRequestEvents(diagnostics.events)
            "RETRIES" -> (it.attempt ?: 1) > 1
            else -> true
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                filters.forEach { value ->
                    TextButton(onClick = { filter = value }) {
                        Text(value, color = if (filter == value) RelayAccent else RelayTextMuted)
                    }
                }
            }
            Text("${events.size} retained events · ${diagnostics.droppedEvents} older events dropped", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        items(events, key = { it.id }) { event ->
            EventRow(event, onClick = { selected = event })
        }
        if (events.isEmpty()) {
            item { Text("No events match this filter.", color = RelayTextMuted, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
    selected?.let { event ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("REQUEST DETAIL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MetricLine("OPERATION", event.operation, event.kind.name)
                    MetricLine("TIME", java.text.DateFormat.getTimeInstance().format(java.util.Date(event.timestamp)), event.transport ?: "transport unavailable")
                    MetricLine("PEER", event.peerNodeId ?: "—", "attempt ${event.attempt ?: 1}")
                    MetricLine("DURATION", event.durationMs?.let { "$it ms" } ?: "—", "bytes ${formatBytes(event.bytes)}")
                    MetricLine("RESULT", event.result ?: "—", "event ${event.id}")
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("CLOSE") } }
        )
    }
}

@Composable
private fun Advanced(
    state: NetworkState,
    transport: com.hybridmesh.relay.messaging.model.GattTransportSnapshot,
    mesh: MeshRuntimeSnapshot,
    diagnostics: NetworkDiagnosticsSnapshot
) {
    val completed = diagnostics.events.filter {
        it.kind == NetworkEventKind.REQUEST_SUCCEEDED ||
            it.kind == NetworkEventKind.REQUEST_FAILED ||
            it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("ACTIVE GATT TRANSPORT") {
                MetricLine("STATE", transport.state.name, transport.error.name)
                MetricLine("PEER", transport.peerAddress ?: "—", transport.messageId ?: "no active message")
                val mtu = transport.mtu
                MetricLine("MTU", mtu?.toString() ?: "—", mtu?.let { "${com.hybridmesh.relay.messaging.protocol.GattPacketCodec.safePayloadBytes(it)} B payload/frame" } ?: "not negotiated")
                MetricLine("FRAMES", "${transport.frameIndex?.plus(1) ?: 0} / ${transport.frameCount ?: 0}", "bytes sent ${formatBytes(transport.bytesSent.toLong())}")
            }
        }
        item {
            SectionCard("RUNTIME COUNTERS") {
                MetricLine("BLE RUNTIME", state.bleRuntimeState.name, "generation ${state.runtimeGeneration}")
                MetricLine("SCAN RESULTS", state.scanResultCount.toString(), "last result ${state.lastScanResultAt?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "—"}")
                MetricLine("FORWARD DELAY", mesh.lastForwardQueueDelayMs?.let { "$it ms" } ?: "—", "latest receive-to-send start")
                MetricLine("LAST SEND", mesh.lastTransportSendDurationMs?.let { "$it ms" } ?: "—", "latest fan-out acceptance")
                MetricLine("AVAILABLE BEARERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" · "), "currently usable next-hop transports")
                MetricLine("SEEN CACHE", mesh.cachedPackets.toString(), "duplicate suppression entries")
                MetricLine("ATTACHMENT RATE", mesh.currentAttachmentThroughputMbps?.let { "${it.twoDecimal()} Mbps" } ?: mesh.lastAttachmentThroughputMbps?.let { "${it.twoDecimal()} Mbps" } ?: "—", if (mesh.attachmentTransferActive) "live verified rate" else "last verified rate")
                mesh.lastDeliveredHopCount?.let { MetricLine("LAST DELIVERY", "$it hops", "destination-confirmed") }
            }
        }
        item {
            SectionCard("EVENT DATASET") {
                MetricLine("RETAINED", diagnostics.events.size.toString(), "bounded process-local metadata")
                MetricLine("COMPLETED", completed.size.toString(), "success, failure, or unavailable")
                MetricLine("DROPPED", diagnostics.droppedEvents.toString(), "oldest events evicted at capacity")
                MetricLine("PAYLOADS", "NOT STORED", "diagnostics contain metadata only")
            }
        }
    }
}

@Composable
private fun EventRow(event: NetworkDiagnosticEvent, onClick: () -> Unit) {
    val status = when (event.kind) {
        NetworkEventKind.REQUEST_SUCCEEDED -> "OK"
        NetworkEventKind.REQUEST_FAILED, NetworkEventKind.REQUEST_UNAVAILABLE -> "ERROR"
        NetworkEventKind.REQUEST_STARTED -> "ACTIVE"
        else -> event.kind.name
    }
    Row(
        Modifier.fillMaxWidth()
            .background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(event.operation, color = RelayAccent, style = TechnicalTextStyle)
            Text("${event.transport ?: "runtime"} · ${event.peerNodeId ?: "local"}", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(status, color = if (status == "ERROR") MaterialTheme.colorScheme.error else RelayAccent, style = TechnicalTextStyle)
            Text(event.durationMs?.let { "$it ms" } ?: "—", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MetricLine(label: String, value: String, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
            Text(detail, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text(value, color = RelayAccent, style = TechnicalTextStyle, modifier = Modifier.padding(start = 10.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "${(bytes / 1_024.0).oneDecimal()} KB"
    else -> "${(bytes / (1_024.0 * 1_024.0)).oneDecimal()} MB"
}

private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this)
private fun Double.twoDecimal(): String = String.format(Locale.US, "%.2f", this)

private fun activeRequestEvents(events: List<NetworkDiagnosticEvent>): List<NetworkDiagnosticEvent> {
    val terminalIds = events.asSequence()
        .filter {
            it.kind == NetworkEventKind.REQUEST_SUCCEEDED ||
                it.kind == NetworkEventKind.REQUEST_FAILED ||
                it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
        }
        .mapNotNull { it.correlationId }
        .toSet()
    return events.filter {
        it.kind == NetworkEventKind.REQUEST_STARTED &&
            (it.correlationId == null || it.correlationId !in terminalIds)
    }
}

private fun bluetoothLabel(state: BluetoothState): String = when (state) {
    BluetoothState.ON -> "ON"
    BluetoothState.OFF -> "OFF"
    BluetoothState.TURNING_ON -> "TURNING ON"
    BluetoothState.TURNING_OFF -> "TURNING OFF"
    BluetoothState.UNSUPPORTED -> "UNSUPPORTED"
    BluetoothState.ERROR -> "ERROR"
}
