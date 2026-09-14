package com.hybridmesh.relay.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.ble.PeerOrderingPolicy
import com.hybridmesh.relay.network.BleRuntimeState
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.DevicesViewModel

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val viewModel: DevicesViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val knownPeers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val ble = PermissionManager.ble(context)

    val orderedLive = PeerOrderingPolicy.stableLive(state.peers)
    val deviceRows = PeerOrderingPolicy.mergeKnownWithLive(knownPeers, orderedLive)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DEVICES", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Known devices, live proximity, and controls for recovering BLE.", color = RelayTextMuted)
        }

        item {
            RuntimeCard(
                runtime = state.bleRuntimeState,
                bluetoothState = state.bluetoothState,
                scanState = state.scanningState.name,
                advertiseState = state.advertisingState.name,
                gattReady = state.gattServerReady
            )
        }

        item {
            AccessCard(
                scanGranted = ble.scanGranted,
                advertiseGranted = ble.advertiseGranted,
                connectGranted = ble.connectGranted,
                onOpenAppSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                onOpenBluetooth = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                onOpenBattery = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            )
        }

        item {
            Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOCAL DEVICE", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
                Text(identity.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(identity.nodeId, style = TechnicalTextStyle, color = RelayAccent)
                Text("The mesh service continues independently when this screen is closed.", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
            }
        }

        item { Text("KNOWN DEVICES", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold) }
        if (deviceRows.isEmpty()) {
            item {
                Text("No saved peers yet. Discover a device or add a Node ID from Messages.", color = RelayTextMuted, modifier = Modifier.padding(vertical = 18.dp))
            }
        } else {
            items(deviceRows, key = { it.first.nodeId }) { (peer, live) ->
                KnownPeerRow(peer, live)
            }
        }

        if (knownPeers.none { it.nodeId.equals(identity.nodeId, true) }) {
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun RuntimeCard(runtime: BleRuntimeState, bluetoothState: BluetoothState, scanState: String, advertiseState: String, gattReady: Boolean) {
    val headline = when (runtime) {
        BleRuntimeState.READY -> "BLE runtime ready"
        BleRuntimeState.RECOVERING -> "Recovering BLE runtime"
        BleRuntimeState.STARTING -> "Starting BLE runtime"
        BleRuntimeState.PERMISSION_REQUIRED -> "Bluetooth permissions required"
        BleRuntimeState.BLUETOOTH_OFF -> "Bluetooth is off"
        BleRuntimeState.UNSUPPORTED -> "BLE unsupported"
        BleRuntimeState.DEGRADED -> "BLE runtime degraded"
    }
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("RUNTIME", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text("Bluetooth: ${bluetoothState.name} • GATT: ${if (gattReady) "READY" else "NOT READY"}", style = TechnicalTextStyle, color = RelayAccent)
        Text("Scanner: $scanState • Advertiser: $advertiseState", style = TechnicalTextStyle, color = RelayTextMuted)
    }
}

@Composable
private fun AccessCard(scanGranted: Boolean, advertiseGranted: Boolean, connectGranted: Boolean, onOpenAppSettings: () -> Unit, onOpenBluetooth: () -> Unit, onOpenBattery: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ACCESS & RECOVERY", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        PermissionRow("Scan", scanGranted)
        PermissionRow("Advertise", advertiseGranted)
        PermissionRow("Connect", connectGranted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.weight(1f)) { Text("APP SETTINGS") }
            OutlinedButton(onClick = onOpenBluetooth, modifier = Modifier.weight(1f)) { Text("BLUETOOTH") }
        }
        OutlinedButton(onClick = onOpenBattery, modifier = Modifier.fillMaxWidth()) { Text("BACKGROUND / BATTERY SETTINGS") }
        Text("OEMs may additionally pause background apps. Neyra keeps its core permission model vendor-neutral; use these settings only when an OEM restricts background execution.", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (granted) RelayAccent else MaterialTheme.colorScheme.error)
        Spacer(Modifier.padding(start = 5.dp))
        Text(label, Modifier.weight(1f))
        Text(if (granted) "GRANTED" else "MISSING", style = TechnicalTextStyle, color = if (granted) RelayAccent else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun KnownPeerRow(peer: PeerEntity, live: com.hybridmesh.relay.ble.BlePeer?) {
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.displayName.takeIf { it.isNotBlank() && !it.equals(peer.nodeId, true) } ?: peer.nodeId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(peer.nodeId, style = TechnicalTextStyle, color = RelayAccent)
            }
            Text(if (live != null) "IN RANGE" else "KNOWN", style = TechnicalTextStyle, color = if (live != null) RelayAccent else RelayTextMuted)
        }
        if (live != null) Text("${live.deviceType.name} • ${live.rssi} dBm • seen now", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.padding(top = 6.dp))
        else Text("Last seen: ${peer.lastSeenAt?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "not discovered"}", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted, modifier = Modifier.padding(top = 6.dp))
    }
}
