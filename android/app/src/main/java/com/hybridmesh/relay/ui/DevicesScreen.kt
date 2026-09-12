package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun DevicesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Devices",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Nearby peers and relay infrastructure.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        DeviceCard(
            name = "Alex's Phone",
            role = "PEER",
            state = "CONNECTED",
            transport = "BLE"
        )

        DeviceCard(
            name = "Relay Node A",
            role = "RELAY",
            state = "AVAILABLE",
            transport = "BLE • LoRa"
        )

        DeviceCard(
            name = "Maya's Phone",
            role = "PEER",
            state = "2 HOPS AWAY",
            transport = "RELAY"
        )
    }
}

@Composable
private fun DeviceCard(
    name: String,
    role: String,
    state: String,
    transport: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = role,
                color = RelayAccent,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "●  $state",
            color = RelayAccent,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = transport,
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )
    }
}