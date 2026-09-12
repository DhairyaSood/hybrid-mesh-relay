package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun MessageDetailScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Alex",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Conversation",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )

        MessageBubble(
            text = "Reached the campsite.",
            mine = false,
            transport = "BLE • DELIVERED • 10:42"
        )

        MessageBubble(
            text = "Nice. I'll head over soon.",
            mine = true,
            transport = "RELAY • DELIVERED • 10:44"
        )
    }
}

@Composable
private fun MessageBubble(
    text: String,
    mine: Boolean,
    transport: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Column(
            horizontalAlignment = if (mine) {
                Alignment.End
            } else {
                Alignment.Start
            }
        ) {
            Column(
                modifier = Modifier
                    .background(
                        if (mine) RelayAccent else RelaySurface,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Text(
                    text = text,
                    color = if (mine) {
                        RelayBackground
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Text(
                text = transport,
                color = RelayTextMuted,
                style = TechnicalTextStyle,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}