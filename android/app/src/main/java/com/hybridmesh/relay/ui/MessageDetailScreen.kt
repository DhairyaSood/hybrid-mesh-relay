package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun MessageDetailScreen(
    messageId: String,
    onBack: () -> Unit = {}
) {
    val viewModel: MessagesViewModel = viewModel()

    val messages by viewModel.messages
        .collectAsStateWithLifecycle()

    val message = messages.firstOrNull {
        it.messageId == messageId
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
                text = "MESSAGE UNAVAILABLE",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "This message is no longer available on this device.",
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
    message: MessageRecordEntity
) {
    val isEmergency =
        message.messageType == "EMERGENCY"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "MESSAGE",
                style = TechnicalTextStyle,
                color = RelayTextMuted
            )

            Text(
                text = "To ${message.recipientNodeId}",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "From ${message.senderNodeId}",
                color = RelayTextMuted,
                style = TechnicalTextStyle
            )
        }

        MessageBubble(
            message = message,
            isEmergency = isEmergency
        )

        MessageInfo(
            message = message
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageRecordEntity,
    isEmergency: Boolean
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
                        color = if (isEmergency) {
                            MaterialTheme.colorScheme.error
                        } else {
                            RelayAccent
                        },
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

            Row(
                modifier = Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.status,
                    color = RelayTextMuted,
                    style = TechnicalTextStyle
                )

                Spacer(
                    modifier = Modifier.width(6.dp)
                )

                Text(
                    text = "•",
                    color = RelayTextMuted,
                    style = TechnicalTextStyle
                )

                Spacer(
                    modifier = Modifier.width(6.dp)
                )

                Text(
                    text = message.lastTransport ?: "LOCAL",
                    color = RelayTextMuted,
                    style = TechnicalTextStyle
                )
            }
        }
    }
}

@Composable
private fun MessageInfo(
    message: MessageRecordEntity
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailRow(
            label = "TYPE",
            value = message.messageType
        )

        DetailRow(
            label = "STATUS",
            value = message.status
        )

        DetailRow(
            label = "TRANSPORT",
            value = message.lastTransport ?: "LOCAL"
        )

        DetailRow(
            label = "MESSAGE ID",
            value = message.messageId
        )

        message.deliveredAt?.let {
            DetailRow(
                label = "DELIVERED",
                value = formatTime(it)
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = value,
            color = RelayAccent,
            style = TechnicalTextStyle,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun formatTime(
    timestamp: Long
): String {
    return java.text.SimpleDateFormat(
        "HH:mm:ss",
        java.util.Locale.getDefault()
    ).format(
        java.util.Date(timestamp)
    )
}