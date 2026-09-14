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
    val transport by MessagingManager.getInstance(androidx.compose.ui.platform.LocalContext.current).transport.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("NETWORK", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Observe the current BLE runtime without controlling it from this screen.", color = RelayTextMuted)
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { selectedTab = 0 }) { Text("OVERVIEW", color = if (selectedTab == 0) RelayAccent else RelayTextMuted) }
                TextButton(onClick = { selectedTab = 1 }) { Text("ADVANCED", color = if (selectedTab == 1) RelayAccent else RelayTextMuted) }
            }
        }
        if (selectedTab == 0) Overview(state) else Advanced(state, transport)
    }
}

@Composable
private fun Overview(state: NetworkState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MetricCard("INITIALIZATION", state.initializationState.name, "generation ${state.runtimeGeneration}") }
        item { MetricCard("RUNTIME", state.bleRuntimeState.name, "BLE runtime condition") }
        item { MetricCard("BLUETOOTH", bluetoothLabel(state.bluetoothState), "BLE capability: ${if (state.bleSupported) "available" else "unsupported"}") }
        item { MetricCard("DISCOVERY", state.scanningState.name, "${state.nearbyDeviceCount} nodes in range") }
        item { MetricCard("ADVERTISING", state.advertisingState.name, if (state.gattServerReady) "GATT server ready" else "Waiting for GATT readiness") }
        item { MetricCard("CONNECTIVITY", if (state.internetAvailable) "INTERNET AVAILABLE" else "OFFLINE", "Internet is informational; BLE messaging does not depend on it") }
    }
}

@Composable
private fun Advanced(state: NetworkState, transport: com.hybridmesh.relay.messaging.model.GattTransportSnapshot) {
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