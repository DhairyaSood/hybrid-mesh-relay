package com.hybridmesh.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun AppStatusStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(RelaySurface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "●",
            color = RelayAccent,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "CONNECTED",
            color = RelayAccent,
            style = TechnicalTextStyle
        )

        Text(
            text = "Internet • Mesh ready",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}