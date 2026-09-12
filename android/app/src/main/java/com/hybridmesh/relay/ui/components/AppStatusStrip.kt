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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun AppStatusStrip() {
    val context = LocalContext.current
    val state by NetworkManager
        .getInstance(context)
        .state
        .collectAsStateWithLifecycle()

    val bleLabel = when (state.bluetoothState) {
        BluetoothState.ON -> when (state.advertisingState) {
            BleOperationState.ACTIVE -> "BLE READY"
            BleOperationState.ERROR -> "BLE ERROR"
            else -> "BLE IDLE"
        }
        BluetoothState.OFF -> "BLUETOOTH OFF"
        BluetoothState.UNSUPPORTED -> "BLE UNSUPPORTED"
    }

    val internetLabel = if (state.internetAvailable) "Internet" else "Offline"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(RelaySurface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("●", color = RelayAccent, style = TechnicalTextStyle)
        Text(bleLabel, color = RelayAccent, style = TechnicalTextStyle)
        Text(
            "$internetLabel • ${state.nearbyDeviceCount} nearby",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )
    }
}
