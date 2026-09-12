package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
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

    val context =
        LocalContext.current

    val networkManager =
        NetworkManager.getInstance(context)

    val state by
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

    val sent =
        messages.count {
            it.senderId ==
                localNodeId
        }

    val received =
        messages.count {
            it.senderId !=
                localNodeId
        }

    val queued =
        messages.count {
            it.status.name ==
                "QUEUED"
        }

    val avgRssi =
        if (state.peers.isEmpty()) {
            null
        } else {
            state.peers
                .map { it.rssi }
                .average()
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                RelayBackground
            )
            .padding(18.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Network",
            style =
                MaterialTheme.typography.headlineSmall
        )

        Text(
            text =
                "Live communication paths and mesh state.",
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )

        NetworkMetric(
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

        NetworkMetric(
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

        NetworkMetric(
            label = "Advertising",
            value =
                state.advertisingState
                    .name
        )

        NetworkMetric(
            label = "Discovery",
            value =
                state.scanningState
                    .name
        )

        NetworkMetric(
            label = "Nearby devices",
            value =
                state.nearbyDeviceCount
                    .toString()
        )

        NetworkMetric(
            label = "Relay nodes",
            value =
                state.relayNodeCount
                    .toString()
        )

        NetworkMetric(
            label = "Active transport",
            value =
                if (
                    state.scanningState ==
                        BleOperationState.ACTIVE ||
                    state.advertisingState ==
                        BleOperationState.ACTIVE
                ) {
                    "BLE"
                } else {
                    "NONE"
                }
        )

        NetworkMetric(
            label = "Average RSSI",
            value =
                avgRssi?.let {
                    "${it.toInt()} dBm"
                } ?: "NO DATA"
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            CounterCard(
                label = "SENT",
                value =
                    sent.toString(),
                modifier =
                    Modifier.weight(1f)
            )

            CounterCard(
                label = "RECEIVED",
                value =
                    received.toString(),
                modifier =
                    Modifier.weight(1f)
            )

            CounterCard(
                label = "QUEUED",
                value =
                    queued.toString(),
                modifier =
                    Modifier.weight(1f)
            )
        }

        NetworkMetric(
            label = "Scan results",
            value =
                state.scanResultCount
                    .toString()
        )
    }
}

@Composable
private fun NetworkMetric(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color =
                    RelayBorder,
                shape =
                    RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            text = label,
            color =
                RelayTextMuted,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )

        Text(
            text = value,
            style =
                TechnicalTextStyle,
            color =
                if (
                    value == "ON" ||
                    value == "ACTIVE" ||
                    value == "ONLINE" ||
                    value == "BLE"
                ) {
                    RelayAccent
                } else {
                    MaterialTheme
                        .colorScheme
                        .onSurface
                }
        )
    }
}

@Composable
private fun CounterCard(
    label: String,
    value: String,
    modifier: Modifier =
        Modifier
) {
    Column(
        modifier = modifier
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
    ) {

        Text(
            text = label,
            color =
                RelayTextMuted,
            style =
                MaterialTheme
                    .typography
                    .labelSmall
        )

        Text(
            text = value,
            style =
                TechnicalTextStyle
        )
    }
}
