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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun NetworkScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Network",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Available communication paths and mesh state.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        NetworkMetric(
            label = "Internet",
            value = "AVAILABLE"
        )

        NetworkMetric(
            label = "Bluetooth",
            value = "READY"
        )

        NetworkMetric(
            label = "Nearby devices",
            value = "03"
        )

        NetworkMetric(
            label = "Relay nodes",
            value = "01"
        )

        NetworkMetric(
            label = "Active transport",
            value = "BLE"
        )

        NetworkMetric(
            label = "Connection quality",
            value = "GOOD"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CounterCard(
                label = "SENT",
                value = "142",
                modifier = Modifier.weight(1f)
            )

            CounterCard(
                label = "RECEIVED",
                value = "138",
                modifier = Modifier.weight(1f)
            )

            CounterCard(
                label = "QUEUED",
                value = "04",
                modifier = Modifier.weight(1f)
            )
        }
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
                color = RelaySurface,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = RelayBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = value,
            style = TechnicalTextStyle
        )
    }
}

@Composable
private fun CounterCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = RelaySurface,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = RelayBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
    ) {
        Text(
            text = label,
            color = RelayTextMuted,
            style = MaterialTheme.typography.labelSmall
        )

        Text(
            text = value,
            style = TechnicalTextStyle
        )
    }
}