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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelayEmergency
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

private enum class Priority {
    NORMAL,
    PRIORITY,
    EMERGENCY
}

@Composable
fun ComposeMessageScreen(
    onMessageSent: () -> Unit
) {
    val viewModel: MessagesViewModel = viewModel()

    var recipient by remember {
        mutableStateOf("")
    }

    var messageText by remember {
        mutableStateOf("")
    }

    var priority by remember {
        mutableStateOf(Priority.NORMAL)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            text = "New Message",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Send a message using the best available communication path.",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "RECIPIENT",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        OutlinedTextField(
            value = recipient,
            onValueChange = {
                recipient = it
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("Enter node ID or contact")
            },
            shape = RoundedCornerShape(12.dp)
        )

        Text(
            text = "MESSAGE",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        OutlinedTextField(
            value = messageText,
            onValueChange = {
                messageText = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = {
                Text("Type your message…")
            },
            shape = RoundedCornerShape(14.dp),
            maxLines = 6
        )

        Text(
            text = "PRIORITY",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            PriorityButton(
                text = "Normal",
                selected = priority == Priority.NORMAL,
                modifier = Modifier.weight(1f),
                onClick = {
                    priority = Priority.NORMAL
                }
            )

            PriorityButton(
                text = "Priority",
                selected = priority == Priority.PRIORITY,
                modifier = Modifier.weight(1f),
                onClick = {
                    priority = Priority.PRIORITY
                }
            )

            PriorityButton(
                text = "Emergency",
                selected = priority == Priority.EMERGENCY,
                emergency = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    priority = Priority.EMERGENCY
                }
            )
        }

        Spacer(
            modifier = Modifier.weight(1f)
        )

        Button(
            onClick = {

                val type = when (priority) {
                    Priority.NORMAL -> MessageType.NORMAL
                    Priority.PRIORITY -> MessageType.PRIORITY
                    Priority.EMERGENCY -> MessageType.EMERGENCY
                }

                viewModel.sendMessage(
                    recipientId = recipient,
                    content = messageText,
                    type = type,
                    onSaved = onMessageSent
                )
            },
            enabled = recipient.isNotBlank() && messageText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (
                    priority == Priority.EMERGENCY
                ) {
                    RelayEmergency
                } else {
                    RelayAccent
                },
                contentColor = RelayBackground
            )
        ) {
            Text(
                text = "Send Message",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun PriorityButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    emergency: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                selected && emergency -> RelayEmergency
                selected -> RelayAccent
                else -> RelaySurface
            },
            contentColor = when {
                selected -> RelayBackground
                else -> RelayTextMuted
            }
        )
    ) {
        Text(text)
    }
}