package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationScreen(peerNodeId: String) {
    val viewModel: MessagesViewModel = viewModel()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val peers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val messages by viewModel.observeConversation(peerNodeId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(MessageType.NORMAL) }
    var deleteMessageId by remember { mutableStateOf<String?>(null) }
    var showDeleteChat by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val peer = peers.firstOrNull { it.nodeId.equals(peerNodeId, ignoreCase = true) }
    val displayName = peer?.displayName?.takeIf { it != peerNodeId } ?: peerNodeId
    val isNearby = networkState.peers.any { it.nodeId.equals(peerNodeId, ignoreCase = true) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(peerNodeId, style = MaterialTheme.typography.labelSmall, color = RelayTextMuted, maxLines = 1)
                    }
                    TextButton(onClick = { showDeleteChat = true }) { Text("DELETE CHAT") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isNearby) "IN RANGE • BLE DISCOVERED" else "OUT OF RANGE • NEW MESSAGES WILL QUEUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNearby) RelayAccent else RelayTextMuted,
                    maxLines = 1
                )
                Text("Tap a message to delete your local copy.", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.messageId }) { message ->
                    MessageBubble(
                        message = message,
                        outgoing = message.senderNodeId.equals(identity.nodeId, ignoreCase = true),
                        onClick = { deleteMessageId = message.messageId }
                    )
                }
            }

            Composer(
                draft = draft,
                onDraftChanged = { draft = it },
                type = selectedType,
                onTypeChanged = { selectedType = it },
                onSend = {
                    viewModel.send(peerNodeId, draft, selectedType) { draft = "" }
                }
            )
        }
    }

    deleteMessageId?.let { messageId ->
        val message = messages.firstOrNull { it.messageId == messageId }
        if (message != null) {
            AlertDialog(
                onDismissRequest = { deleteMessageId = null },
                title = { Text("DELETE MESSAGE") },
                text = {
                    Text(
                        if (message.status == DeliveryStatus.DELIVERED.name && !message.senderNodeId.equals(identity.nodeId, true)) {
                            "Delete your local copy. The sender keeps their copy."
                        } else if (message.status == DeliveryStatus.DELIVERED.name) {
                            "Delete your local copy. The recipient keeps their copy."
                        } else {
                            "This message is still local/queued. It has not been delivered yet."
                        }
                    )
                },
                confirmButton = {
                    Button(onClick = { viewModel.deleteMessage(messageId); deleteMessageId = null }) { Text("DELETE") }
                },
                dismissButton = { TextButton(onClick = { deleteMessageId = null }) { Text("CANCEL") } }
            )
        }
    }

    if (showDeleteChat) {
        AlertDialog(
            onDismissRequest = { showDeleteChat = false },
            title = { Text("DELETE CHAT") },
            text = { Text("Remove the local conversation history. The peer identity remains saved, and the other device keeps its copies.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteConversation(peerNodeId); showDeleteChat = false }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { showDeleteChat = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageRecordEntity,
    outgoing: Boolean,
    onClick: () -> Unit
) {
    val background = if (outgoing && message.messageType == MessageType.EMERGENCY.name) {
        MaterialTheme.colorScheme.error
    } else {
        RelaySurface
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(background, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                message.content,
                color = if (outgoing && message.messageType == MessageType.EMERGENCY.name) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${formatTime(message.createdAt)} • ${statusLabel(message.status)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (outgoing && message.messageType == MessageType.EMERGENCY.name) MaterialTheme.colorScheme.onError.copy(alpha = .8f) else RelayTextMuted
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChanged: (String) -> Unit,
    type: MessageType,
    onTypeChanged: (MessageType) -> Unit,
    onSend: () -> Unit
) {
    Surface(shadowElevation = 2.dp, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MessageTypeButton(MessageType.NORMAL, type == MessageType.NORMAL, onTypeChanged, Modifier.weight(1f))
                MessageTypeButton(MessageType.PRIORITY, type == MessageType.PRIORITY, onTypeChanged, Modifier.weight(1f))
                MessageTypeButton(MessageType.EMERGENCY, type == MessageType.EMERGENCY, onTypeChanged, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChanged(it.take(2_048)) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    minLines = 1,
                    maxLines = 5
                )
                Button(onClick = onSend, enabled = draft.isNotBlank()) { Text("SEND") }
            }
        }
    }
}

@Composable
private fun MessageTypeButton(
    value: MessageType,
    selected: Boolean,
    onSelected: (MessageType) -> Unit,
    modifier: Modifier
) {
    TextButton(onClick = { onSelected(value) }, modifier = modifier) {
        Text(
            value.name,
            color = if (selected) RelayAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

private fun statusLabel(status: String): String = when (status) {
    DeliveryStatus.QUEUED.name -> "QUEUED"
    DeliveryStatus.IN_FLIGHT.name -> "SENDING"
    DeliveryStatus.DELIVERED.name -> "DELIVERED"
    DeliveryStatus.FAILED.name -> "FAILED"
    else -> status
}
