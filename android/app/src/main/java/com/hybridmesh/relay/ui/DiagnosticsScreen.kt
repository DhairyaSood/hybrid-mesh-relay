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
fun DiagnosticsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "System state and communication telemetry.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        DiagnosticRow("Bluetooth", "READY")
        DiagnosticRow("Wi-Fi transport", "IDLE")
        DiagnosticRow("Mesh", "READY")
        DiagnosticRow("Packets sent", "142")
        DiagnosticRow("Packets received", "138")
        DiagnosticRow("Packets queued", "04")
        DiagnosticRow("Packets dropped", "00")
        DiagnosticRow("Node ID", "NODE-7F2A")
        DiagnosticRow("Protocol", "HMR-1.0")
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
            .background(RelaySurface, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
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