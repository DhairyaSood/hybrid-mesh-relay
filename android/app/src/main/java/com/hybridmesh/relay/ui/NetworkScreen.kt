package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun NetworkScreen() {
    val viewModel: MessagesViewModel = viewModel()
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val messagingManager = MessagingManager.getInstance(androidx.compose.ui.platform.LocalContext.current)
    val transport by messagingManager.transport.collectAsStateWithLifecycle()
    val mesh by messagingManager.mesh.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("NETWORK", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("See Bluetooth LE and Wi-Fi Direct discovery, transport health, and message delivery.", color = RelayTextMuted)
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { selectedTab = 0 }) { Text("OVERVIEW", color = if (selectedTab == 0) RelayAccent else RelayTextMuted) }
                TextButton(onClick = { selectedTab = 1 }) { Text("ADVANCED", color = if (selectedTab == 1) RelayAccent else RelayTextMuted) }
            }
        }
        if (selectedTab == 0) Overview(state, mesh, meshPeers.size) else Advanced(state, transport, mesh)
    }
}

@Composable
private fun Overview(state: NetworkState, mesh: com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot, nearbyNodeCount: Int) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MetricCard("INITIALIZATION", state.initializationState.name, "generation ${state.runtimeGeneration}") }
        item { MetricCard("MESH SERVICE", if (mesh.running) "RUNNING" else "STOPPED", "$nearbyNodeCount unique nearby nodes") }
        item { MetricCard("BLUETOOTH LE RUNTIME", state.bleRuntimeState.name, "Bluetooth-specific discovery and GATT status") }
        item { MetricCard("BLUETOOTH", bluetoothLabel(state.bluetoothState), "BLE capability: ${if (state.bleSupported) "available" else "unsupported"}") }
        item { MetricCard("BLUETOOTH LE DISCOVERY", state.scanningState.name, "${state.nearbyDeviceCount} nodes seen over BLE") }
        item { MetricCard("BLE ADVERTISING / GATT", state.advertisingState.name, if (state.gattServerReady) "GATT server ready" else "Waiting for GATT readiness") }
        item { MetricCard("CONNECTIVITY", if (state.internetAvailable) "INTERNET AVAILABLE" else "OFFLINE", "Internet is informational; BLE messaging does not depend on it") }
        item {
            val wifiState = when {
                mesh.wifiDirectDiscoveryActive -> "ACTIVE"
                mesh.connectedWifiPeers > 0 -> "CONNECTED"
                else -> "STARTING / UNAVAILABLE"
            }
            MetricCard("WI-FI DIRECT", wifiState, "${mesh.connectedWifiPeers} established peer sessions")
        }
        item { MetricCard("MESH FORWARDING", if (mesh.running) "READY" else "STOPPED", "relay ${if (mesh.relayEnabled) "enabled" else "disabled"} • forwarding ${mesh.pendingForwarding}") }
        item { MetricCard("AVAILABLE BEARERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" • "), "Transport paths currently able to send to discovered peers") }
        item { MetricCard("FORWARDING DELAY", mesh.lastForwardQueueDelayMs?.let { "$it ms" } ?: "—", "receive to forwarding send start; includes suppression window") }
        item { MetricCard("LAST SEND", mesh.lastTransportSendDurationMs?.let { "$it ms" } ?: "—", "bounded fan-out duration including transport acceptance") }
        item { MetricCard("MESH CACHE", mesh.cachedPackets.toString(), "active packet identities") }
    }
}

@Composable
private fun Advanced(state: NetworkState, transport: com.hybridmesh.relay.messaging.model.GattTransportSnapshot, mesh: com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MetricCard("TRANSPORT STATE", transport.state.name, transport.error.name) }
        item { MetricCard("PEER", transport.peerAddress ?: "—", transport.messageId ?: "No active message") }
        item {
            val mtu = transport.mtu ?: 23
            MetricCard(
                "MTU",
                mtu.toString(),
                "payload/frame ${com.hybridmesh.relay.messaging.protocol.GattPacketCodec.safePayloadBytes(mtu)} B"
            )
        }
        item { MetricCard("FRAMES", "${transport.frameIndex?.plus(1) ?: 0} / ${transport.frameCount ?: 0}", "bytes sent ${transport.bytesSent}") }
        item { MetricCard("RUNTIME", state.bleRuntimeState.name, "generation ${state.runtimeGeneration}") }
        item { MetricCard("NODE DISCOVERY", "${state.nearbyDeviceCount} live", "phones ${state.phoneNodeCount} • relays ${state.relayNodeCount}") }
        item { MetricCard("FORWARD QUEUE", mesh.pendingForwarding.toString(), "durable transit packets") }
        item { MetricCard("AVAILABLE BEARERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" • "), "currently usable next-hop transports") }
        item { MetricCard("WI-FI PEERS", mesh.connectedWifiPeers.toString(), "established socket sessions") }
        item { MetricCard("FORWARD QUEUE DELAY", mesh.lastForwardQueueDelayMs?.let { "$it ms" } ?: "—", "receive to send start, includes suppression") }
        item { MetricCard("LAST SEND DURATION", mesh.lastTransportSendDurationMs?.let { "$it ms" } ?: "—", "fan-out including transport ACK") }
        item { MetricCard("SEEN CACHE", mesh.cachedPackets.toString(), "duplicate suppression entries") }
        mesh.lastDeliveredHopCount?.let { item { MetricCard("LAST DELIVERY", "$it hops", "destination-confirmed") } }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String) {
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.titleMedium, color = RelayAccent)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
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
