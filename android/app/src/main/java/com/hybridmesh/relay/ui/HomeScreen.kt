package com.hybridmesh.relay.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot
import com.hybridmesh.relay.network.BleRuntimeState
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.components.rememberWifiEnabled
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelayNode
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(onNetworkClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val networkState by NetworkManager.getInstance(context).state.collectAsStateWithLifecycle()
    val identity by IdentityStore.getInstance(context).identity.collectAsStateWithLifecycle()
    val messagingManager = MessagingManager.getInstance(context)
    val mesh by messagingManager.mesh.collectAsStateWithLifecycle()
    val peers by messagingManager.meshPeers.collectAsStateWithLifecycle()
    val wifiEnabled = rememberWifiEnabled()
    val wifiSupported = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
    val wifiPermissionGranted = PermissionManager.wifiDirectPermissionGranted(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("NEYRA", style = MaterialTheme.typography.displaySmall, maxLines = 1)
                Text(identity.deviceName, style = MaterialTheme.typography.titleSmall, color = RelayAccent, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                Text(identity.nodeId, style = TechnicalTextStyle, color = RelayTextMuted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item {
            MeshStatusCard(
                state = networkState,
                mesh = mesh,
                peerCount = peers.size,
                wifiEnabled = wifiEnabled,
                wifiSupported = wifiSupported,
                wifiPermissionGranted = wifiPermissionGranted,
                onClick = onNetworkClick
            )
        }
        item { InternetStatusCard(isAvailable = networkState.internetAvailable, onClick = onNetworkClick) }
        item { MeshPreview(peers) }
    }
}

@Composable
private fun MeshStatusCard(
    state: NetworkState,
    mesh: MeshRuntimeSnapshot,
    peerCount: Int,
    wifiEnabled: Boolean,
    wifiSupported: Boolean,
    wifiPermissionGranted: Boolean,
    onClick: () -> Unit
) {
    val bleReady = state.bleSupported && state.permissionsGranted &&
        state.bluetoothState == BluetoothState.ON && state.scanningState == BleOperationState.ACTIVE
    val wifiReady = wifiSupported && wifiEnabled && wifiPermissionGranted &&
        (mesh.wifiDirectDiscoveryActive || mesh.connectedWifiPeers > 0)
    val headline = when {
        bleReady && wifiReady -> "Nearby mesh active over both transports"
        wifiReady -> "Nearby mesh active over Wi-Fi Direct"
        bleReady -> "Nearby mesh active over Bluetooth LE"
        mesh.running -> "Mesh service running · transports starting"
        else -> "Mesh service starting"
    }
    Column(
        Modifier.fillMaxWidth()
            .background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MESH STATUS", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.weight(1f))
            Text(if (mesh.running) "RUNNING" else "STARTING", style = TechnicalTextStyle, color = if (mesh.running) RelayAccent else RelayTextMuted)
        }
        Text(headline, style = MaterialTheme.typography.titleMedium)
        TransportStatusLine("Bluetooth LE", bleStatus(state), bleReady)
        TransportStatusLine("Wi-Fi Direct", wifiStatus(wifiEnabled, wifiSupported, wifiPermissionGranted, mesh), wifiReady)
        Text("$peerCount nearby node${if (peerCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
        Text("Tap for network details", style = TechnicalTextStyle, color = RelayTextMuted)
    }
}

@Composable
private fun TransportStatusLine(label: String, status: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (ready) RelayAccent else RelayTextMuted)
        Spacer(Modifier.padding(start = 6.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(status, style = TechnicalTextStyle, color = if (ready) RelayAccent else RelayTextMuted)
    }
}

private fun bleStatus(state: NetworkState): String = when {
    !state.bleSupported -> "Unsupported"
    !state.permissionsGranted -> "Permission needed"
    state.bluetoothState != BluetoothState.ON -> state.bluetoothState.name.lowercase().replace('_', ' ')
    state.scanningState == BleOperationState.ACTIVE -> "Discovering"
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
private fun InternetStatusCard(isAvailable: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("INTERNET", style = TechnicalTextStyle, color = RelayTextMuted)
        Text(if (isAvailable) "Available" else "Offline", style = MaterialTheme.typography.titleMedium)
        Text("Nearby messaging uses Bluetooth LE and Wi-Fi Direct; internet is optional.", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
    }
}

@Composable
private fun MeshPreview(peers: List<MeshPeer>) {
    val transition = rememberInfiniteTransition(label = "meshPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), repeatMode = RepeatMode.Reverse),
        label = "pulse"
    )
    val bleOnly = peers.count { !it.bleAddress.isNullOrBlank() && it.wifiHost.isNullOrBlank() }
    val wifiOnly = peers.count { it.bleAddress.isNullOrBlank() && !it.wifiHost.isNullOrBlank() }
    val both = peers.count { !it.bleAddress.isNullOrBlank() && !it.wifiHost.isNullOrBlank() }
    val visiblePeers = peers.sortedByDescending { it.lastSeen }.take(8)

    Column(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(16.dp)
    ) {
        Text("LOCAL MESH", color = RelayTextMuted, style = TechnicalTextStyle)
        Text("Nodes are deduplicated by ID; dual-radio nodes use a double ring.", color = RelayTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                visiblePeers.forEachIndexed { index, peer ->
                    val angle = 2.0 * Math.PI * index / visiblePeers.size - Math.PI / 2.0
                    val radius = minOf(size.width, size.height) * 0.31f
                    val node = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                    drawLine(
                        color = RelayTextMuted.copy(alpha = 0.45f), start = center, end = node,
                        strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                    val hasBle = !peer.bleAddress.isNullOrBlank()
                    val hasWifi = !peer.wifiHost.isNullOrBlank() && (peer.wifiPort ?: 0) > 0
                    drawCircle(color = if (hasWifi && !hasBle) RelayAccent else RelayNode, radius = 7.dp.toPx(), center = node)
                    if (hasWifi && hasBle) drawCircle(color = RelayAccent, radius = 9.dp.toPx(), center = node, style = Stroke(width = 2.dp.toPx()))
                }
                drawCircle(color = RelayAccent.copy(alpha = pulse), radius = 12.dp.toPx(), center = center)
            }
            Text("YOU", Modifier.align(Alignment.Center), color = RelayBackground, style = TechnicalTextStyle)
            if (peers.isEmpty()) {
                Text("No nearby nodes", Modifier.align(Alignment.BottomCenter), color = RelayTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("${peers.size} nearby node${if (peers.size == 1) "" else "s"}", color = RelayTextMuted, style = MaterialTheme.typography.bodySmall)
        Text("$bleOnly via Bluetooth LE · $wifiOnly via Wi-Fi Direct · $both via both", color = RelayTextMuted, style = MaterialTheme.typography.bodySmall)
    }
}
