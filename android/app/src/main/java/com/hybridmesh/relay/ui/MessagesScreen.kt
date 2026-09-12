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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.model.Message
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen(
    onNewMessage: () -> Unit,
    onConversationClick: (String) -> Unit
) {
    val viewModel: MessagesViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()

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

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        if (messages.isEmpty()) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No messages yet.",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Messages you create will be stored locally on this device.",
                    color = RelayTextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

        } else {

            messages.forEach { message ->

                MessageListItem(
                    message = message,
                    onClick = {
                        onConversationClick(message.id)
                    }
                )
            }
        }

        Spacer(
            modifier = Modifier.weight(1f)
        )

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
            Text(
                text = "New Message"
            )
        }
    }
}

@Composable
private fun MessageListItem(
    message: Message,
    onClick: () -> Unit
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
            .clickable(
                onClick = onClick
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = message.recipientId,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = "${message.status.name} • ${message.type.name}",
                color = RelayTextMuted,
                style = TechnicalTextStyle
            )
        }

        Text(
            text = formatMessageTime(message.timestamp),
            color = RelayTextMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatMessageTime(
    timestamp: Long
): String {
    return SimpleDateFormat(
        "HH:mm",
        Locale.getDefault()
    ).format(
        Date(timestamp)
    )
}