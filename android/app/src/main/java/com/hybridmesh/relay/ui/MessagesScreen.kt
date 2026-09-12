package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

@Composable
fun MessagesScreen(
    onNewMessage: () -> Unit,
    onConversationClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Messages",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Communication across available paths.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        MessageListItem(
            name = "Alex",
            message = "Reached the campsite.",
            time = "10:42",
            state = "DELIVERED • BLE",
            onClick = onConversationClick
        )

        MessageListItem(
            name = "Maya",
            message = "Where are you?",
            time = "10:45",
            state = "DELIVERED • RELAY",
            onClick = onConversationClick
        )

        MessageListItem(
            name = "Sam",
            message = "I'll send the location shortly.",
            time = "11:02",
            state = "QUEUED • MESH",
            onClick = onConversationClick
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onNewMessage,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RelayAccent,
                contentColor = RelayBackground
            )
        ) {
            Text("New Message")
        }
    }
}

@Composable
private fun MessageListItem(
    name: String,
    message: String,
    time: String,
    state: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = state,
                color = RelayTextMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Text(
            text = time,
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}