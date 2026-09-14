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
import com.hybridmesh.relay.network.InitializationState
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

    val label = when (state.initializationState) {
        InitializationState.BOOTSTRAPPING -> "STARTING"
        InitializationState.CHECKING_PERMISSIONS -> "CHECKING PERMISSIONS"
        InitializationState.STARTING_BLE -> "BLE STARTING"
        InitializationState.STARTING_GATT -> "GATT STARTING"
        InitializationState.STARTING_DISCOVERY -> "DISCOVERY STARTING"
        InitializationState.READY -> "BLE READY"
        InitializationState.RECOVERING -> "RECOVERING"
        InitializationState.DEGRADED -> "BLE DEGRADED"
        InitializationState.BLUETOOTH_OFF -> "BLUETOOTH OFF"
        InitializationState.PERMISSION_REQUIRED -> "BLE PERMISSION"
    }

    val internetLabel = if (state.internetAvailable) "Internet" else "Offline"
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).background(RelaySurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("●", color = RelayAccent, style = TechnicalTextStyle)
        Text(label, color = RelayAccent, style = TechnicalTextStyle)
        Text("$internetLabel • ${state.nearbyDeviceCount} nearby", color = RelayTextMuted, style = TechnicalTextStyle)
    }
}
