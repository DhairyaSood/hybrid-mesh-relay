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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun DiagnosticsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val network by NetworkManager.getInstance(context).state.collectAsStateWithLifecycle()
    val viewModel: MessagesViewModel = viewModel()
    val sent by viewModel.sentCount.collectAsStateWithLifecycle()
    val received by viewModel.receivedCount.collectAsStateWithLifecycle()
    val queued by viewModel.queuedCount.collectAsStateWithLifecycle()
    val transport by MessagingManager.getInstance(context).transport.collectAsStateWithLifecycle()
    val identity by IdentityStore.getInstance(context).identity.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("DIAGNOSTICS", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text("Live system state and communication telemetry.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { DiagnosticRow("BLE capability", if (network.bleSupported) "YES" else "NO") }
        item { DiagnosticRow("Bluetooth", when (network.bluetoothState) {
            BluetoothState.ON -> "ON"
            BluetoothState.OFF -> "OFF"
            BluetoothState.UNSUPPORTED -> "UNSUPPORTED"
        }) }
        item { DiagnosticRow("Permissions", if (network.permissionsGranted) "GRANTED" else "MISSING") }
        item { DiagnosticRow("Advertising", network.advertisingState.name) }
        item { DiagnosticRow("Scanning", network.scanningState.name) }
        item { DiagnosticRow("Nearby peers", network.nearbyDeviceCount.toString()) }
        item { DiagnosticRow("Phone peers", network.phoneNodeCount.toString()) }
        item { DiagnosticRow("Relay peers", network.relayNodeCount.toString()) }
        item { DiagnosticRow("Scan callbacks", network.scanResultCount.toString()) }
        item { DiagnosticRow("Messages sent", sent.toString()) }
        item { DiagnosticRow("Messages received", received.toString()) }
        item { DiagnosticRow("Messages queued", queued.toString()) }
        item { DiagnosticRow("GATT transport", transport.state.name) }
        item { DiagnosticRow("GATT error", transport.error.name) }
        item { DiagnosticRow("GATT MTU", transport.mtu?.toString() ?: "—") }
        transport.frameCount?.let {
            item { DiagnosticRow("GATT frame", "${transport.frameIndex?.plus(1) ?: 0}/$it") }
        }
        item { DiagnosticRow("Internet", if (network.internetAvailable) "AVAILABLE" else "OFFLINE") }
        item { DiagnosticRow("Node ID", identity.nodeId) }
        item { DiagnosticRow("Protocol", "HMR-DISCOVERY/2 + HMR-GATT/1") }
        network.advertisingErrorCode?.let { code ->
            item { DiagnosticRow("Advertising error", code.toString()) }
        }
        network.scanningErrorCode?.let { code ->
            item { DiagnosticRow("Scanning error", code.toString()) }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.dp, RelayBorder, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = RelayTextMuted, modifier = Modifier.weight(1f), maxLines = 2)
        Text(
            value,
            color = RelayAccent,
            style = TechnicalTextStyle,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 3
        )
    }
}
