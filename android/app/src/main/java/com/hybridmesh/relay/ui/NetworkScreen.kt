package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun NetworkScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: MessagesViewModel = viewModel()
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val sent by viewModel.sentCount.collectAsStateWithLifecycle()
    val received by viewModel.receivedCount.collectAsStateWithLifecycle()
    val queued by viewModel.queuedCount.collectAsStateWithLifecycle()
    val knownPeers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val identity by IdentityStore.getInstance(context).identity.collectAsStateWithLifecycle()
    val transport by MessagingManager.getInstance(context).transport.collectAsStateWithLifecycle()
    var selectedView by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(RelayBackground)) {
        Text(
            "NETWORK",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        )
        PrimaryTabRow(selectedTabIndex = selectedView) {
            Tab(
                selected = selectedView == 0,
                onClick = { selectedView = 0 },
                text = { Text("OVERVIEW", maxLines = 1) }
            )
            Tab(
                selected = selectedView == 1,
                onClick = { selectedView = 1 },
                text = { Text("ADVANCED", maxLines = 1) }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedView == 0) {
                item { SectionLabel("CONNECTIVITY") }
                item { MetricRow("Bluetooth", bluetoothLabel(state.bluetoothState)) }
                item { MetricRow("BLE capability", if (state.bleSupported) "AVAILABLE" else "UNSUPPORTED") }
                item { MetricRow("Internet", if (state.internetAvailable) "AVAILABLE" else "OFFLINE") }
                item { MetricRow("Permissions", if (state.permissionsGranted) "GRANTED" else "MISSING") }

                item { SectionLabel("MESH") }
                item { MetricRow("Nearby nodes", state.nearbyDeviceCount.toString()) }
                item { MetricRow("Phone nodes", state.phoneNodeCount.toString()) }
                item { MetricRow("Relay nodes", state.relayNodeCount.toString()) }
                item { MetricRow("Scanning", state.scanningState.name) }
                item { MetricRow("Advertising", state.advertisingState.name) }

                item { SectionLabel("MESSAGING") }
                item { MetricRow("Messages sent", sent.toString()) }
                item { MetricRow("Messages received", received.toString()) }
                item { MetricRow("Messages queued", queued.toString()) }
            } else {
                item { SectionLabel("ACTIVE TRANSPORT") }
                item { MetricRow("State", transport.state.name) }
                item { MetricRow("Error", transport.error.name) }
                item { MetricRow("MTU", transport.mtu?.toString() ?: "—") }
                item { MetricRow("Frame", transport.frameCount?.let { "${(transport.frameIndex ?: 0) + 1}/$it" } ?: "—") }
                item { MetricRow("Bytes sent", transport.bytesSent.toString()) }
                item { MetricRow("Protocol", "HMR-DISCOVERY/2 + HMR-GATT/1") }
                item { MetricRow("Node ID", identity.nodeId) }
                item { MetricRow("Peer address", transport.peerAddress ?: "—") }
                item { MetricRow("Message ID", transport.messageId ?: "—") }

                item { SectionLabel("BLE DIAGNOSTICS") }
                item { MetricRow("Scan callbacks", state.scanResultCount.toString()) }
                state.advertisingErrorCode?.let { code -> item { MetricRow("Advertising error", code.toString()) } }
                state.scanningErrorCode?.let { code -> item { MetricRow("Scanning error", code.toString()) } }

                if (state.peers.isNotEmpty()) {
                    item { SectionLabel("LIVE PEERS") }
                    items(state.peers, key = { it.nodeId }) { peer ->
                        MetricRow(
                            peer.deviceName.ifBlank { peer.nodeId },
                            "${peer.rssi} dBm • ${peer.deviceType.name} • ${peer.nodeId}"
                        )
                    }
                }
            }
        }
    }
}

private fun bluetoothLabel(state: BluetoothState): String = when (state) {
    BluetoothState.ON -> "ON"
    BluetoothState.OFF -> "OFF"
    BluetoothState.UNSUPPORTED -> "UNSUPPORTED"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = TechnicalTextStyle,
        color = RelayTextMuted,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(14.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(14.dp))
            .padding(15.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = RelayTextMuted, modifier = Modifier.weight(1f), maxLines = 2)
        Text(value, color = RelayAccent, style = TechnicalTextStyle, modifier = Modifier.padding(start = 10.dp), maxLines = 3)
    }
}
