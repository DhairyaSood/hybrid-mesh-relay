package com.hybridmesh.relay.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot
import com.hybridmesh.relay.ble.PeerOrderingPolicy
import com.hybridmesh.relay.network.BleRuntimeState
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.components.rememberWifiEnabled
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.components.NodeIdText
import com.hybridmesh.relay.ui.viewmodel.DevicesViewModel

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val viewModel: DevicesViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val knownPeers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val meshRuntime by viewModel.meshRuntime.collectAsStateWithLifecycle()
    val ble = PermissionManager.ble(context)
    val wifiEnabled = rememberWifiEnabled()
    val wifiSupported = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
    val wifiPermissionGranted = PermissionManager.wifiDirectPermissionGranted(context)

    val orderedLive = PeerOrderingPolicy.stableLive(state.peers)
    val deviceRows = PeerOrderingPolicy.mergeKnownWithLive(knownPeers, orderedLive)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DEVICES", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Known devices and live proximity across Bluetooth LE and Wi-Fi Direct.", color = RelayTextMuted)
        }

        item {
            RuntimeCard(
                state = state,
                mesh = meshRuntime,
                nearbyNodeCount = meshPeers.size,
                wifiEnabled = wifiEnabled,
                wifiSupported = wifiSupported,
                wifiPermissionGranted = wifiPermissionGranted
            )
        }

        item {
            AccessCard(
                scanGranted = ble.scanGranted,
                advertiseGranted = ble.advertiseGranted,
                connectGranted = ble.connectGranted,
                wifiDirectGranted = wifiPermissionGranted,
                onOpenAppSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                onOpenBluetooth = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                onOpenWifi = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
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
                NodeIdText(identity.nodeId, style = TechnicalTextStyle, color = RelayAccent)
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
                val liveWifi = meshPeers.firstOrNull {
                    it.nodeId.equals(peer.nodeId, true) &&
                        !it.wifiHost.isNullOrBlank() && (it.wifiPort ?: 0) > 0
                }
                KnownPeerRow(peer, live, liveWifi) {
                    clipboard.setText(AnnotatedString(peer.nodeId))
                    Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (knownPeers.none { it.nodeId.equals(identity.nodeId, true) }) {
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun RuntimeCard(
    state: NetworkState,
    mesh: MeshRuntimeSnapshot,
    nearbyNodeCount: Int,
    wifiEnabled: Boolean,
    wifiSupported: Boolean,
    wifiPermissionGranted: Boolean
) {
    val bleReady = state.bleSupported && state.permissionsGranted && state.bluetoothState == BluetoothState.ON && state.scanningState.name == "ACTIVE"
    val wifiReady = wifiSupported && wifiEnabled && wifiPermissionGranted &&
        (mesh.wifiDirectDiscoveryActive || mesh.connectedWifiPeers > 0)
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MESH RUNTIME", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(if (mesh.running) "Mesh service running" else "Mesh service starting", style = MaterialTheme.typography.titleMedium)
        RuntimeTransportRow("Bluetooth LE", bleStatus(state), bleReady)
        RuntimeTransportRow("Wi-Fi Direct", wifiStatus(wifiEnabled, wifiSupported, wifiPermissionGranted, mesh), wifiReady)
        Text("$nearbyNodeCount nearby node${if (nearbyNodeCount == 1) "" else "s"} · ${mesh.connectedWifiPeers} active Wi-Fi Direct session${if (mesh.connectedWifiPeers == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
    }
}

@Composable
private fun RuntimeTransportRow(label: String, status: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (ready) RelayAccent else RelayTextMuted)
        Spacer(Modifier.padding(start = 5.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(status, style = TechnicalTextStyle, color = if (ready) RelayAccent else RelayTextMuted)
    }
}

private fun bleStatus(state: NetworkState): String = when {
    !state.bleSupported -> "Unsupported"
    !state.permissionsGranted -> "Permission needed"
    state.bluetoothState != BluetoothState.ON -> state.bluetoothState.name.lowercase().replace('_', ' ')
    state.scanningState.name == "ACTIVE" -> "Discovering"
    state.bleRuntimeState == BleRuntimeState.STARTING || state.bleRuntimeState == BleRuntimeState.RECOVERING -> "Starting"
    else -> state.bleRuntimeState.name.lowercase().replace('_', ' ')
}

private fun wifiStatus(enabled: Boolean, supported: Boolean, permissionGranted: Boolean, mesh: MeshRuntimeSnapshot): String = when {
    !supported -> "Unsupported"
    !permissionGranted -> "Permission needed"
    !enabled -> "Wi-Fi is off"
    mesh.connectedWifiPeers > 0 -> "Connected"
    mesh.wifiDirectDiscoveryActive -> "Discovering"
    else -> "Starting"
}

@Composable
private fun AccessCard(scanGranted: Boolean, advertiseGranted: Boolean, connectGranted: Boolean, wifiDirectGranted: Boolean, onOpenAppSettings: () -> Unit, onOpenBluetooth: () -> Unit, onOpenWifi: () -> Unit, onOpenBattery: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MESH ACCESS & RECOVERY", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        PermissionRow("Scan", scanGranted)
        PermissionRow("Advertise", advertiseGranted)
        PermissionRow("Connect", connectGranted)
        PermissionRow("Wi-Fi Direct", wifiDirectGranted)
        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) { Text("APP PERMISSIONS") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenWifi, modifier = Modifier.weight(1f)) { Text("WI-FI SETTINGS") }
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
private fun KnownPeerRow(peer: PeerEntity, live: com.hybridmesh.relay.ble.BlePeer?, liveWifi: MeshPeer?, onCopyNodeId: () -> Unit) {
    val inRange = live != null || liveWifi != null
    Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.displayName.takeIf { it.isNotBlank() && !it.equals(peer.nodeId, true) } ?: peer.nodeId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                NodeIdText(peer.nodeId, style = TechnicalTextStyle, color = RelayAccent)
            }
            Text(if (inRange) "IN RANGE" else "KNOWN", style = TechnicalTextStyle, color = if (inRange) RelayAccent else RelayTextMuted)
        }
        if (live != null || liveWifi != null) {
            val transports = buildList {
                if (live != null) add("BLE")
                if (liveWifi != null) add("Wi-Fi Direct")
            }.joinToString(" · ")
            Text("$transports • seen now", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.padding(top = 6.dp))
        }
        else Text("Last seen: ${peer.lastSeenAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "not discovered"}", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted, modifier = Modifier.padding(top = 6.dp))
    }
}
