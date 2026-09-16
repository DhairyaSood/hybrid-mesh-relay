package com.hybridmesh.relay.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.location.LocationManager
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.BleRuntimeState
import com.hybridmesh.relay.notifications.MessagingNotificationCoordinator
import com.hybridmesh.relay.permissions.LocationPermissionLevel
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ConversationScreen(peerNodeId: String) {
    val context = LocalContext.current
    val viewModel: MessagesViewModel = viewModel()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val peers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val messages by viewModel.observeConversation(peerNodeId).collectAsStateWithLifecycle(initialValue = emptyList())
    val locationManager = remember { LocationManager(context) }
    val notificationCoordinator = remember { MessagingNotificationCoordinator.getInstance(context) }
    val scope = rememberCoroutineScope()

    var draft by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf(MessageType.NORMAL.name) }
    var deleteMessageId by remember { mutableStateOf<String?>(null) }
    var showDeleteChat by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }

    val peer = peers.firstOrNull { it.nodeId.equals(peerNodeId, true) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() && !it.equals(peerNodeId, true) } ?: peerNodeId

    DisposableEffect(peerNodeId) {
        notificationCoordinator.clearConversation(peerNodeId)
        onDispose { }
    }

    fun sendText() {
        val text = draft.trim()
        if (text.isBlank()) return
        val type = runCatching { MessageType.valueOf(selectedType) }.getOrDefault(MessageType.NORMAL)
        viewModel.send(peerNodeId, text, type) { draft = "" }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val precise = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val approximate = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!precise && !approximate) {
            locationError = "Location permission was not granted."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val location = locationManager.getCurrentLocation()
            if (location == null) {
                locationError = "Could not get a current location. Check that a location provider is enabled."
            } else {
                viewModel.sendLocation(
                    peerNodeId = peerNodeId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.takeIf { it >= 0f },
                    timestamp = location.time
                )
                locationError = null
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (networkState.peers.any { it.nodeId.equals(peerNodeId, true) }) {
                                "IN RANGE"
                            } else {
                                "NOT IN RANGE"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = RelayTextMuted
                        )
                    }

                    TextButton(onClick = { showDeleteChat = true }) {
                        Text(
                            "DELETE CHAT",
                            color = RelayTextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Text(
                    peerNodeId,
                    style = MaterialTheme.typography.labelSmall,
                    color = RelayAccent
                )

                if (networkState.bleRuntimeState != BleRuntimeState.READY) {
                    Text(
                        when (networkState.bleRuntimeState) {
                            BleRuntimeState.PERMISSION_REQUIRED -> "Queued locally until Bluetooth access is available."
                            BleRuntimeState.BLUETOOTH_OFF -> "Queued locally until Bluetooth is turned on."
                            else -> "Queued locally until the BLE transport is ready."
                        },
                        color = RelayTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item { EmptyConversationState(displayName) }
                } else {
                    var previousDateKey: String? = null
                    messages.forEach { message ->
                        val dateKey = conversationDateKey(message.createdAt)
                        if (dateKey != previousDateKey) {
                            item(key = "date-$dateKey") { ConversationDateMarker(message.createdAt) }
                            previousDateKey = dateKey
                        }
                        item(key = message.messageId) {
                            val incoming = !message.senderNodeId.equals(identity.nodeId, true)
                            MessageBubble(message, incoming, onLongPress = { deleteMessageId = message.messageId })
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    IconButton(
                        onClick = {
                            locationError = null
                            val state = PermissionManager.location(context)
                            when (state.level) {
                                LocationPermissionLevel.PRECISE,
                                LocationPermissionLevel.APPROXIMATE -> {
                                    scope.launch {
                                        val location = locationManager.getCurrentLocation()
                                        if (location == null) locationError = "Could not get your current location."
                                        else viewModel.sendLocation(peerNodeId, location.latitude, location.longitude, location.accuracy.takeIf { it >= 0f }, location.time)
                                    }
                                }
                                else -> {
                                    PermissionManager.markLocationRequestAttempted(context)
                                    locationPermissionLauncher.launch(PermissionManager.locationPermissionsToRequest())
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "Share location",
                            tint = RelayAccent
                        )
                    }

                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 4,
                        placeholder = { Text("Message") }
                    )

                    Spacer(Modifier.size(6.dp))
                    Button(onClick = ::sendText, enabled = draft.trim().isNotBlank(), modifier = Modifier.height(46.dp)) {
                        Text("SEND")
                    }
                }

                if (locationError != null) {
                    Text(locationError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 46.dp, top = 4.dp))
                }

                Row(Modifier.padding(start = 46.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(MessageType.NORMAL, MessageType.PRIORITY, MessageType.EMERGENCY).forEach { type ->
                        TextButton(onClick = { selectedType = type.name }) {
                            Text(type.name, color = if (selectedType == type.name) RelayAccent else RelayTextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    deleteMessageId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { deleteMessageId = null },
            title = { Text("DELETE MESSAGE") },
            text = { Text("Remove this message from this device?") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteMessage(messageId); deleteMessageId = null }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { deleteMessageId = null }) { Text("CANCEL") } }
        )
    }

    if (showDeleteChat) {
        AlertDialog(
            onDismissRequest = { showDeleteChat = false },
            title = { Text("DELETE CHAT") },
            text = { Text("Remove this conversation from this device? This does not send anything to the other node.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteConversation(peerNodeId); showDeleteChat = false }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { showDeleteChat = false }) { Text("CANCEL") } }
        )
    }
}


private fun conversationDateKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun ConversationDateMarker(timestamp: Long) {
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    val label = when {
        sameCalendarDay(messageCalendar, today) -> "TODAY"
        sameCalendarDay(messageCalendar, yesterday) -> "YESTERDAY"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = RelayTextMuted)
    }
}

private fun sameCalendarDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

@Composable
private fun EmptyConversationState(displayName: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("NO MESSAGES YET", fontWeight = FontWeight.Bold, color = RelayAccent)
        Text("Start a conversation with $displayName.", color = RelayTextMuted)
    }
}

@Composable
private fun MessageBubble(message: MessageRecordEntity, incoming: Boolean, onLongPress: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val payload = if (message.messageType == MessageType.LOCATION.name) LocationPayload.decode(message.content) else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 310.dp).background(if (incoming) RelaySurface else RelayAccent.copy(alpha = 0.16f), shape).border(1.dp, RelayBorder, shape).padding(12.dp)
                .clickable(onClick = onLongPress)
        ) {
            if (payload != null) {
                Text("LOCATION", style = MaterialTheme.typography.labelSmall, color = RelayAccent, fontWeight = FontWeight.Bold)
                Text("${LocationPayload.formatCoordinate(payload.latitude)}, ${LocationPayload.formatCoordinate(payload.longitude)}", color = MaterialTheme.colorScheme.onSurface)
                payload.accuracyMeters?.let { Text("Accuracy ±${it.toInt()} m", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted) }
            } else {
                Text(message.content, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt)), style = MaterialTheme.typography.labelSmall, color = RelayTextMuted)
                if (!incoming) Text(
                    when {
                        message.status == DeliveryStatus.DELIVERED.name && (message.deliveryHopCount ?: 0) > 0 ->
                            "DELIVERED · ${message.deliveryHopCount} HOPS"
                        message.status == DeliveryStatus.DELIVERED.name -> "DELIVERED"
                        message.status == DeliveryStatus.IN_FLIGHT.name -> "SENDING"
                        message.status == DeliveryStatus.QUEUED.name -> "QUEUED"
                        message.status == DeliveryStatus.FAILED.name -> "FAILED"
                        else -> ""
                    }, style = MaterialTheme.typography.labelSmall, color = if (message.status == DeliveryStatus.FAILED.name) MaterialTheme.colorScheme.error else RelayTextMuted
                )
            }
        }
    }
}