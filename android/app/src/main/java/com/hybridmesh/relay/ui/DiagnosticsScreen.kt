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
import com.hybridmesh.relay.data.IdentityStore
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

    val nodeId =
        IdentityStore(context)
            .getIdentity()
            .nodeId

    val sent =
        messages.count {
            it.senderId == nodeId
        }

    val received =
        messages.count {
            it.senderId != nodeId
        }

    val queued =
        messages.count {
            it.status.name == "QUEUED"
        }

    val failed =
        messages.count {
            it.status.name == "FAILED"
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                RelayBackground
            )
            .padding(18.dp),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {

        Text(
            text = "Diagnostics",
            style =
                MaterialTheme
                    .typography
                    .headlineSmall
        )

        Text(
            text =
                "Live system state and communication telemetry.",
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )

        DiagnosticRow(
            label = "BLE support",
            value =
                if (state.bleSupported) {
                    "YES"
                } else {
                    "NO"
                }
        )

        DiagnosticRow(
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

        DiagnosticRow(
            label = "Permissions",
            value =
                if (
                    state.permissionsGranted
                ) {
                    "GRANTED"
                } else {
                    "MISSING"
                }
        )

        DiagnosticRow(
            label = "Advertising",
            value =
                state.advertisingState
                    .name
        )

        DiagnosticRow(
            label = "Scanning",
            value =
                state.scanningState
                    .name
        )

        DiagnosticRow(
            label = "Nearby peers",
            value =
                state.nearbyDeviceCount
                    .toString()
        )

        DiagnosticRow(
            label = "Phone peers",
            value =
                state.phoneNodeCount
                    .toString()
        )

        DiagnosticRow(
            label = "Relay peers",
            value =
                state.relayNodeCount
                    .toString()
        )

        DiagnosticRow(
            label = "Scan results",
            value =
                state.scanResultCount
                    .toString()
        )

        DiagnosticRow(
            label = "Messages sent",
            value =
                sent.toString()
        )

        DiagnosticRow(
            label = "Messages received",
            value =
                received.toString()
        )

        DiagnosticRow(
            label = "Messages queued",
            value =
                queued.toString()
        )

        DiagnosticRow(
            label = "Messages failed",
            value =
                failed.toString()
        )

        DiagnosticRow(
            label = "Node ID",
            value =
                nodeId
        )

        DiagnosticRow(
            label = "Protocol",
            value =
                "HMR-DISCOVERY/1"
        )

        state.advertisingErrorCode?.let {
            DiagnosticRow(
                label =
                    "Advertising error",
                value =
                    it.toString()
            )
        }

        state.scanningErrorCode?.let {
            DiagnosticRow(
                label =
                    "Scanning error",
                value =
                    it.toString()
            )
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
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
                    value == "YES" ||
                    value == "GRANTED"
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
