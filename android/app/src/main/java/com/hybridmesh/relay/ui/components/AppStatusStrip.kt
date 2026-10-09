package com.hybridmesh.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun AppStatusStrip() {
    val context = LocalContext.current
    val state by NetworkManager.getInstance(context).state.collectAsStateWithLifecycle()
    val manager = MessagingManager.getInstance(context)
    val mesh by manager.mesh.collectAsStateWithLifecycle()
    val peers by manager.meshPeers.collectAsStateWithLifecycle()
    val wifiEnabled = rememberWifiEnabled()
    val wifiSupported = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
    val wifiPermissionGranted = PermissionManager.wifiDirectPermissionGranted(context)

    val bleReady = state.bleSupported && state.permissionsGranted &&
        state.bluetoothState == BluetoothState.ON && state.scanningState == BleOperationState.ACTIVE
    val wifiReady = wifiSupported && wifiEnabled && wifiPermissionGranted &&
        (mesh.wifiDirectDiscoveryActive || mesh.connectedWifiPeers > 0)
    val activeBearers = buildList {
        if (bleReady) add("BLE")
        if (wifiReady) add("Wi-Fi Direct")
    }
    val label = when {
        activeBearers.size == 2 -> "MESH ACTIVE · BLE + WI-FI DIRECT"
        bleReady -> "MESH ACTIVE · BLE"
        wifiReady -> "MESH ACTIVE · WI-FI DIRECT"
        mesh.running -> "MESH RUNNING · CONNECTING"
        else -> "MESH STARTING"
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).background(RelaySurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("●", color = if (activeBearers.isNotEmpty()) RelayAccent else RelayTextMuted, style = TechnicalTextStyle)
        Text(label, color = RelayAccent, style = TechnicalTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${peers.size} nearby", color = RelayTextMuted, style = TechnicalTextStyle, maxLines = 1)
    }
}
