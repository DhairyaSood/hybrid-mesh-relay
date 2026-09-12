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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.model.Message
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun MessageDetailScreen(
    messageId: String
) {
    val viewModel: MessagesViewModel = viewModel()

    val messages by viewModel.messages.collectAsState()

    val message = messages.firstOrNull {
        it.id == messageId
    }

    if (message == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(RelayBackground)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Message unavailable",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "This message is no longer available on the device.",
                color = RelayTextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        return
    }

    ActualMessageDetail(
        message = message
    )
}

@Composable
private fun ActualMessageDetail(
    message: Message
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            text = message.recipientId,
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Message",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )

        MessageBubble(
            message = message
        )
    }
}

@Composable
private fun MessageBubble(
    message: Message
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End
        ) {

            Column(
                modifier = Modifier
                    .background(
                        color = RelayAccent,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Text(
                    text = message.content,
                    color = RelayBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Text(
                text = "${message.status.name} • ${message.type.name}",
                color = RelayTextMuted,
                style = TechnicalTextStyle,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}