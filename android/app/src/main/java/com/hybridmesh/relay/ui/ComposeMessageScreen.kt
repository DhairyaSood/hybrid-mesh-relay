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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelayEmergency
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

private enum class Priority {
    NORMAL,
    PRIORITY,
    EMERGENCY
}

@Composable
fun ComposeMessageScreen() {

    var priority by mutableStateOf(Priority.NORMAL)

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
            text = "Choose how this message should be handled.",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "RECIPIENT",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Select contact")
        }

        Text(
            text = "MESSAGE",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    color = RelaySurface,
                    shape = RoundedCornerShape(14.dp)
                )
                .border(
                    width = 1.dp,
                    color = RelayBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = "Type your message…",
                color = RelayTextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

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
            onClick = { },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (priority == Priority.EMERGENCY) {
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