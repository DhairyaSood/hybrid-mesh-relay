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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkState
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "NEYRA",
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2
                )

                Text(
                    text = identity.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = RelayAccent,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Text(
                    text = identity.nodeId,
                    style = TechnicalTextStyle,
                    color = RelayTextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        item {
            RuntimeStatusCard(state = networkState)
        }

        item {
            NetworkStatusCard(
                state = networkState,
                onClick = onNetworkClick
            )
        }

        item {
            MeshPreview(networkState.peers)
        }
    }
}

@Composable
private fun RuntimeStatusCard(state: NetworkState) {
    val active = state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.READY
    val headline = when (state.bleRuntimeState) {
        com.hybridmesh.relay.network.BleRuntimeState.READY -> "Mesh runtime ready"
        com.hybridmesh.relay.network.BleRuntimeState.RECOVERING -> "Recovering mesh runtime"
        com.hybridmesh.relay.network.BleRuntimeState.STARTING -> "Starting mesh runtime"
        com.hybridmesh.relay.network.BleRuntimeState.PERMISSION_REQUIRED -> "Permission required"
        com.hybridmesh.relay.network.BleRuntimeState.BLUETOOTH_OFF -> "Bluetooth is off"
        com.hybridmesh.relay.network.BleRuntimeState.UNSUPPORTED -> "BLE unsupported"
        com.hybridmesh.relay.network.BleRuntimeState.DEGRADED -> "Mesh runtime degraded"
    }
    Column(Modifier.fillMaxWidth().background(RelaySurface, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).border(1.dp, RelayBorder, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RUNTIME", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.weight(1f))
            Text(state.initializationState.name, style = TechnicalTextStyle, color = if (active) RelayAccent else RelayTextMuted)
        }
        Text(headline, style = MaterialTheme.typography.titleMedium)
        if (state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.STARTING || state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.RECOVERING) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text("${state.nearbyDeviceCount} nearby • generation ${state.runtimeGeneration}", style = TechnicalTextStyle, color = RelayTextMuted)
    }
}

@Composable
private fun NetworkStatusCard(
    state: NetworkState,
    onClick: () -> Unit
) {
    val headline = when (state.bluetoothState) {
        BluetoothState.UNSUPPORTED -> "BLE unsupported"
        BluetoothState.OFF -> "Bluetooth is off"
        BluetoothState.TURNING_ON -> "Bluetooth is turning on"
        BluetoothState.TURNING_OFF -> "Bluetooth is turning off"
        BluetoothState.ERROR -> "Bluetooth unavailable"
        BluetoothState.ON -> when {
            state.scanningState == BleOperationState.ACTIVE ->
                "Discovery active"

            state.advertisingState == BleOperationState.ACTIVE ->
                "Mesh available"

            else ->
                "BLE ready"
        }
    }

    val detail = when {
        !state.permissionsGranted ->
            "Bluetooth permissions are required."

        state.bluetoothState == BluetoothState.OFF ->
            "Turn Bluetooth on to discover nearby nodes."

        state.scanningState == BleOperationState.ACTIVE ->
            "Discovering nearby Hybrid Mesh nodes."

        else ->
            "BLE advertising is ${state.advertisingState.name.lowercase()}."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "NETWORK STATUS",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "${state.nearbyDeviceCount} nearby • ${
                if (state.internetAvailable) {
                    "internet available"
                } else {
                    "offline"
                }
            }",
            style = TechnicalTextStyle,
            color = RelayTextMuted
        )
    }
}

@Composable
private fun MeshPreview(peers: List<BlePeer>) {
    val transition = rememberInfiniteTransition(label = "meshPulse")

    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "LOCAL MESH",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val center = Offset(
                    x = size.width * 0.5f,
                    y = size.height * 0.5f
                )

                val visiblePeers = peers.take(8)

                if (visiblePeers.isNotEmpty()) {
                    visiblePeers.forEachIndexed { index, _ ->
                        val angle =
                            2.0 * Math.PI * index / visiblePeers.size -
                                Math.PI / 2.0

                        val radius =
                            minOf(size.width, size.height) * 0.31f

                        val node = Offset(
                            x = center.x +
                                radius * cos(angle).toFloat(),
                            y = center.y +
                                radius * sin(angle).toFloat()
                        )

                        drawLine(
                            color = RelayTextMuted.copy(alpha = 0.45f),
                            start = center,
                            end = node,
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(10f, 10f)
                            )
                        )

                        drawCircle(
                            color = RelayNode,
                            radius = 7.dp.toPx(),
                            center = node
                        )
                    }
                }

                drawCircle(
                    color = RelayAccent.copy(alpha = pulse),
                    radius = 12.dp.toPx(),
                    center = center
                )
            }

            Text(
                text = "YOU",
                modifier = Modifier.align(Alignment.Center),
                color = RelayBackground,
                style = TechnicalTextStyle
            )

            if (peers.isEmpty()) {
                Text(
                    text = "No nearby nodes",
                    modifier = Modifier.align(Alignment.BottomCenter),
                    color = RelayTextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text(
            text = "${peers.size} nearby node${if (peers.size == 1) "" else "s"}",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}