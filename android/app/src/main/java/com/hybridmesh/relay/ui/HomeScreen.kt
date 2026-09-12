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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.model.Message
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
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    onMessagesClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onNewMessage: () -> Unit,
    onMessageClick: (String) -> Unit,
    onProfileClick: () -> Unit
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current

    val networkManager =
        NetworkManager.getInstance(context)

    val networkState by
        networkManager.state
            .collectAsStateWithLifecycle()

    val messagesViewModel:
            MessagesViewModel =
        viewModel()

    val messages by
        messagesViewModel.messages
            .collectAsStateWithLifecycle()

    val localNodeId =
        IdentityStore(context)
            .getIdentity()
            .nodeId

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(horizontal = 18.dp),
        verticalArrangement =
            Arrangement.spacedBy(16.dp)
    ) {

        item {

            Spacer(
                modifier =
                    Modifier.height(14.dp)
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            "HYBRID MESH RELAY",
                        style =
                            MaterialTheme
                                .typography
                                .displaySmall
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "Resilient communication network",
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )
                }

                Text(
                    text = "PROFILE",
                    color =
                        RelayAccent,
                    style =
                        TechnicalTextStyle,
                    fontWeight =
                        FontWeight.Bold,
                    modifier = Modifier
                        .clickable(
                            onClick =
                                onProfileClick
                        )
                        .padding(
                            horizontal = 8.dp,
                            vertical = 8.dp
                        )
                )
            }
        }

        item {
            NetworkStatusCard(
                state = networkState,
                onClick =
                    onNetworkClick
            )
        }

        item {
            MeshPreview(
                peers =
                    networkState.peers
            )
        }

        item {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text =
                        "Recent messages",
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )

                Text(
                    text = "VIEW ALL",
                    color =
                        RelayAccent,
                    style =
                        TechnicalTextStyle,
                    modifier =
                        Modifier.clickable(
                            onClick =
                                onMessagesClick
                        )
                )
            }
        }

        if (messages.isEmpty()) {

            item {
                EmptyRecentMessages()
            }

        } else {

            messages
                .take(3)
                .forEach { message ->

                    item(
                        key = message.id
                    ) {
                        RecentMessage(
                            message = message,
                            localNodeId =
                                localNodeId,
                            onClick = {
                                onMessageClick(
                                    message.id
                                )
                            }
                        )
                    }
                }
        }

        item {

            Spacer(
                modifier =
                    Modifier.height(4.dp)
            )

            Button(
                onClick = onNewMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape =
                    RoundedCornerShape(14.dp),
                colors =
                    ButtonDefaults
                        .buttonColors(
                            containerColor =
                                RelayAccent,
                            contentColor =
                                RelayBackground
                        )
            ) {
                Text(
                    text = "New Message",
                    style =
                        MaterialTheme
                            .typography
                            .labelLarge
                )
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )
        }
    }
}

@Composable
private fun NetworkStatusCard(
    state: NetworkState,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(18.dp)
            )
            .clickable(
                onClick = onClick
            )
            .padding(18.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Column {
                Text(
                    text =
                        "NETWORK STATUS",
                    color =
                        RelayTextMuted,
                    style =
                        TechnicalTextStyle
                )

                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )

                Text(
                    text =
                        networkSummary(
                            state
                        ),
                    color =
                        when (
                            state.bluetoothState
                        ) {
                            BluetoothState.ON ->
                                RelayAccent

                            else ->
                                MaterialTheme
                                    .colorScheme
                                    .error
                        },
                    style =
                        MaterialTheme
                            .typography
                            .titleLarge
                )
            }

            Text(
                text =
                    if (
                        state.bluetoothState ==
                            BluetoothState.ON
                    ) {
                        "●"
                    } else {
                        "○"
                    },
                color =
                    if (
                        state.bluetoothState ==
                            BluetoothState.ON
                    ) {
                        RelayAccent
                    } else {
                        MaterialTheme
                            .colorScheme
                            .error
                    },
                style =
                    MaterialTheme
                        .typography
                        .titleLarge
            )
        }

        Spacer(
            modifier =
                Modifier.height(16.dp)
        )

        StatusRow(
            label = "Internet",
            value =
                if (
                    state.internetAvailable
                ) {
                    "ONLINE"
                } else {
                    "OFFLINE"
                }
        )

        StatusRow(
            label = "Bluetooth",
            value =
                when (
                    state.bluetoothState
                ) {
                    BluetoothState.ON ->
                        "ON"

                    BluetoothState.OFF ->
                        "OFF"

                    BluetoothState.UNSUPPORTED ->
                        "UNSUPPORTED"
                }
        )

        StatusRow(
            label = "BLE discovery",
            value =
                when (
                    state.scanningState
                ) {
                    BleOperationState.ACTIVE ->
                        "ACTIVE"

                    BleOperationState.STARTING ->
                        "STARTING"

                    else ->
                        "IDLE"
                }
        )

        StatusRow(
            label = "Nearby devices",
            value =
                state.nearbyDeviceCount
                    .toString()
        )

        StatusRow(
            label = "Relay nodes",
            value =
                state.relayNodeCount
                    .toString()
        )
    }
}

@Composable
private fun MeshPreview(
    peers: List<BlePeer>
) {

    val transition =
        rememberInfiniteTransition(
            label = "meshPulse"
        )

    val pulse by
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(1600),
                    repeatMode =
                        RepeatMode.Reverse
                ),
            label = "pulse"
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {

        Text(
            text = "LOCAL MESH",
            color =
                RelayTextMuted,
            style =
                TechnicalTextStyle
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {

            Canvas(
                modifier =
                    Modifier.fillMaxSize()
            ) {

                val center =
                    androidx.compose.ui.geometry.Offset(
                        size.width * 0.5f,
                        size.height * 0.5f
                    )

                val visiblePeers =
                    peers.take(8)

                visiblePeers
                    .forEachIndexed { index, peer ->

                        val angle =
                            (
                                2.0 *
                                    Math.PI *
                                    index /
                                    visiblePeers.size
                            ) -
                            Math.PI / 2.0

                        val radius =
                            minOf(
                                size.width,
                                size.height
                            ) * 0.31f

                        val node =
                            androidx.compose.ui.geometry.Offset(
                                x =
                                    center.x +
                                        radius *
                                        cos(angle)
                                            .toFloat(),

                                y =
                                    center.y +
                                        radius *
                                        sin(angle)
                                            .toFloat()
                            )

                        drawLine(
                            color =
                                RelayTextMuted
                                    .copy(
                                        alpha = 0.45f
                                    ),
                            start =
                                center,
                            end =
                                node,
                            strokeWidth =
                                2.dp.toPx(),
                            pathEffect =
                                PathEffect
                                    .dashPathEffect(
                                        floatArrayOf(
                                            10f,
                                            10f
                                        )
                                    )
                        )

                        drawCircle(
                            color =
                                RelayNode,
                            radius =
                                7.dp.toPx(),
                            center =
                                node
                        )
                    }

                drawCircle(
                    color =
                        RelayAccent.copy(
                            alpha = pulse
                        ),
                    radius =
                        12.dp.toPx(),
                    center =
                        center
                )
            }

            Text(
                text = "YOU",
                modifier =
                    Modifier.align(
                        Alignment.Center
                    ),
                color =
                    RelayBackground,
                style =
                    TechnicalTextStyle
            )

            if (peers.isEmpty()) {
                Text(
                    text =
                        "No nearby nodes",
                    modifier =
                        Modifier
                            .align(
                                Alignment.BottomCenter
                            )
                            .padding(
                                bottom = 4.dp
                            ),
                    color =
                        RelayTextMuted,
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(6.dp)
        )

        Text(
            text =
                "${peers.size} nearby node" +
                    if (
                        peers.size == 1
                    ) {
                        ""
                    } else {
                        "s"
                    },
            color =
                RelayTextMuted,
            style =
                MaterialTheme
                    .typography
                    .bodySmall
        )
    }
}

@Composable
private fun EmptyRecentMessages() {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(18.dp)
    ) {

        Text(
            text =
                "NO RECENT MESSAGES",
            style =
                TechnicalTextStyle,
            color =
                RelayTextMuted,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(6.dp)
        )

        Text(
            text =
                "Messages you send or receive will appear here.",
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )
    }
}

@Composable
private fun RecentMessage(
    message: Message,
    localNodeId: String,
    onClick: () -> Unit
) {

    val isOutgoing =
        message.senderId ==
            localNodeId

    val sender =
        if (isOutgoing) {
            "To ${message.recipientId}"
        } else {
            message.senderId
        }

    val transport =
        message.route?.transport
            ?: "LOCAL"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(
                onClick = onClick
            )
            .padding(15.dp),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text = sender,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(3.dp)
            )

            Text(
                text = message.content,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                maxLines = 1
            )

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            Row {
                Text(
                    text =
                        "$transport • ${message.status.name}",
                    color =
                        if (
                            message.type.name ==
                                "EMERGENCY"
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            RelayTextMuted
                        },
                    style =
                        TechnicalTextStyle
                )
            }
        }

        Text(
            text =
                formatTime(
                    message.timestamp
                ),
            color =
                RelayTextMuted,
            style =
                MaterialTheme
                    .typography
                    .bodySmall
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 5.dp
                ),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            text = label,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )

        Text(
            text = value,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurface,
            style =
                TechnicalTextStyle
        )
    }
}

private fun networkSummary(
    state: NetworkState
): String {

    return when {

        state.bluetoothState !=
            BluetoothState.ON ->
            "Bluetooth unavailable"

        state.scanningState ==
            BleOperationState.ACTIVE ->
            "Discovery active"

        state.advertisingState ==
            BleOperationState.ACTIVE ->
            "Mesh available"

        else ->
            "BLE ready"
    }
}

private fun formatTime(
    timestamp: Long
): String {

    return SimpleDateFormat(
        "HH:mm",
        Locale.getDefault()
    ).format(
        Date(timestamp)
    )
}
