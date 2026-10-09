package com.hybridmesh.relay.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.messaging.data.AttachmentRecordEntity
import com.hybridmesh.relay.location.LocationManager
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferStatus
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ConversationScreen(peerNodeId: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val viewModel: MessagesViewModel = viewModel()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val peers by viewModel.knownPeers.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val meshRuntime by viewModel.meshRuntime.collectAsStateWithLifecycle()
    val messages by viewModel.observeConversation(peerNodeId).collectAsStateWithLifecycle(initialValue = emptyList())
    val attachments by viewModel.observeConversationAttachments(peerNodeId).collectAsStateWithLifecycle(initialValue = emptyList())
    val conversationListState = rememberLazyListState()
    val locationManager = remember { LocationManager(context) }
    val notificationCoordinator = remember { MessagingNotificationCoordinator.getInstance(context) }
    val scope = rememberCoroutineScope()

    var draft by rememberSaveable { mutableStateOf("") }
    var deleteMessageId by remember { mutableStateOf<String?>(null) }
    var diagnosticsMessageId by remember { mutableStateOf<String?>(null) }
    var showDeleteChat by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var selectedVideoPath by remember { mutableStateOf<String?>(null) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            attachmentError = null
            viewModel.sendAttachment(peerNodeId, uri) { success, error ->
                if (!success) attachmentError = error ?: "Could not add this attachment."
            }
        }
    }

    val peer = peers.firstOrNull { it.nodeId.equals(peerNodeId, true) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() && !it.equals(peerNodeId, true) } ?: "Unknown node"
    val nearbyPeer = meshPeers.firstOrNull { it.nodeId.equals(peerNodeId, true) }
    val inRange = nearbyPeer?.hasAnyEndpoint() == true

    LaunchedEffect(peerNodeId, messages.size) {
        if (messages.isNotEmpty()) {
            val dateMarkerCount = messages.map { conversationDateKey(it.createdAt) }.distinct().size
            val lastItemIndex = messages.size + dateMarkerCount - 1
            conversationListState.scrollToItem(lastItemIndex)
        }
    }

    DisposableEffect(peerNodeId) {
        notificationCoordinator.clearConversation(peerNodeId)
        onDispose { }
    }

    fun sendText() {
        val text = draft.trim()
        if (text.isBlank()) return
        viewModel.send(peerNodeId, text, MessageType.NORMAL) { draft = "" }
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
                            if (inRange) {
                                val transports = buildList {
                                    if (!nearbyPeer?.bleAddress.isNullOrBlank()) add("BLE")
                                    if (!nearbyPeer?.wifiHost.isNullOrBlank()) add("WI-FI DIRECT")
                                }
                                "IN RANGE · ${transports.joinToString(" · ")}"
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
                    color = RelayAccent,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(peerNodeId))
                        android.widget.Toast.makeText(context, "Node ID copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )

                if (meshRuntime.availableTransports.isEmpty()) {
                    Text(
                        "Messages stay on this phone until Wi-Fi Direct or Bluetooth is available.",
                        color = RelayTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else if (!inRange) {
                    Text(
                        "Messages are saved and will send when this node is nearby.",
                        color = RelayTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = conversationListState,
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
                            MessageBubble(
                                message = message,
                                attachment = attachments.firstOrNull { it.messageId == message.messageId },
                                incoming = incoming,
                                onClick = { deleteMessageId = message.messageId },
                                onLongPress = { diagnosticsMessageId = message.messageId },
                                onOpenVideo = { selectedVideoPath = it },
                                onOpenImage = { selectedImagePath = it }
                            )
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Message") },
                        trailingIcon = {
                            IconButton(
                                onClick = { attachmentPicker.launch(arrayOf("image/*", "video/*")) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Add photo or video",
                                    tint = RelayAccent
                                )
                            }
                        }
                    )

                    IconButton(
                        onClick = ::sendText,
                        enabled = draft.trim().isNotBlank(),
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(
                            if (draft.trim().isNotBlank()) RelayAccent else RelayBorder
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                if (locationError != null) {
                    Text(locationError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 46.dp, top = 4.dp))
                }
                if (attachmentError != null) {
                    Text(attachmentError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 46.dp, top = 4.dp))
                }

            }
        }
    }

    diagnosticsMessageId?.let { messageId ->
        val selectedMessage = messages.firstOrNull { it.messageId == messageId }
        val traceEvents by viewModel.observeTraceEvents(messageId).collectAsStateWithLifecycle(initialValue = emptyList())
        if (selectedMessage != null) {
            MessageDiagnosticsDialog(
                message = selectedMessage,
                events = traceEvents,
                localNodeId = identity.nodeId,
                onDismiss = { diagnosticsMessageId = null }
            )
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

    selectedImagePath?.let { path ->
        AttachmentImageViewer(path, onDismiss = { selectedImagePath = null })
    }

    selectedVideoPath?.let { path ->
        var player by remember(path) { mutableStateOf<VideoView?>(null) }
        DisposableEffect(path) {
            onDispose { player?.stopPlayback() }
        }
        Dialog(
            onDismissRequest = { selectedVideoPath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { playerContext ->
                            VideoView(playerContext).apply {
                                player = this
                                val controller = MediaController(playerContext)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoPath(path)
                                setOnPreparedListener { it.start() }
                            }
                        }
                    )
                    TextButton(
                        onClick = { selectedVideoPath = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) { Text("CLOSE", color = Color.White) }
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageRecordEntity,
    attachment: AttachmentRecordEntity?,
    incoming: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenImage: (String) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val payload = if (message.messageType == MessageType.LOCATION.name) LocationPayload.decode(message.content) else null
    val descriptor = if (message.messageType == MessageType.ATTACHMENT.name) {
        com.hybridmesh.relay.messaging.attachment.AttachmentDescriptor.decode(message.content)
    } else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 310.dp).background(if (incoming) RelaySurface else RelayAccent.copy(alpha = 0.16f), shape).border(1.dp, RelayBorder, shape).padding(12.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
        ) {
            if (descriptor != null) {
                val isVideo = descriptor.mimeType.startsWith("video/")
                val localPath = attachment?.localPath?.takeIf { java.io.File(it).isFile }
                if (localPath != null) {
                    if (isVideo) {
                        AttachmentVideoPreview(localPath, onClick = { onOpenVideo(localPath) })
                    } else {
                        AttachmentImagePreview(localPath, onClick = { onOpenImage(localPath) })
                    }
                }
                if (!isVideo || localPath == null) {
                    Text(if (isVideo) "VIDEO" else "IMAGE", style = MaterialTheme.typography.labelSmall, color = RelayAccent, fontWeight = FontWeight.Bold)
                    Text(attachment?.displayName ?: descriptor.displayName, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(formatAttachmentSize(descriptor.sizeBytes), style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
                val state = attachment?.status ?: AttachmentTransferStatus.WAITING_WIFI
                val progress = attachment?.transferredBytes ?: 0L
                Text(
                    when (state) {
                        AttachmentTransferStatus.DELIVERED -> if (attachment?.localPath != null) "AVAILABLE" else "DELIVERED"
                        AttachmentTransferStatus.SENDING -> "SENDING · ${formatAttachmentSize(progress)}"
                        AttachmentTransferStatus.RECEIVING -> "RECEIVING · ${formatAttachmentSize(progress)}"
                        AttachmentTransferStatus.RELAYED -> "FORWARDED · AWAITING RECIPIENT"
                        AttachmentTransferStatus.RELAY_QUEUED -> "WAITING FOR WI-FI RELAY"
                        AttachmentTransferStatus.FAILED -> "TRANSFER FAILED"
                        AttachmentTransferStatus.QUEUED,
                        AttachmentTransferStatus.WAITING_WIFI -> "WAITING FOR WI-FI DIRECT"
                        else -> "WAITING FOR WI-FI DIRECT"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == AttachmentTransferStatus.FAILED) MaterialTheme.colorScheme.error else RelayTextMuted
                )
            } else if (payload != null) {
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

@Composable
private fun AttachmentImagePreview(path: String, onClick: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeAttachmentThumbnail(path) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Image attachment preview",
            modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).padding(top = 8.dp).clickable(onClick = onClick),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun AttachmentVideoPreview(path: String, onClick: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeVideoThumbnail(path) }
    }
    Box(
        Modifier.fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Video preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        androidx.compose.material3.Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.65f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

private fun decodeAttachmentThumbnail(path: String): Bitmap? = runCatching {
    decodeAttachmentBitmap(path, maxDimension = 640)
}.getOrNull()

private fun decodeVideoThumbnail(path: String): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 360
            val scale = 640f / maxOf(width, height).coerceAtLeast(1)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight
            ) ?: retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight
            )
        } else {
            (
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                )?.let { frame ->
                    val scale = 640f / maxOf(frame.width, frame.height).coerceAtLeast(1)
                    if (scale >= 1f) frame else Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
        }
    } finally {
        retriever.release()
    }
}.getOrNull()

private fun decodeAttachmentBitmap(path: String, maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) })
}.getOrNull()

@Composable
private fun AttachmentImageViewer(path: String, onDismiss: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(path) { mutableStateOf(1f) }
    var translation by remember(path) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        translation = if (nextScale <= 1f) Offset.Zero else translation + panChange
    }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeAttachmentBitmap(path, maxDimension = 2048) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                bitmap?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "Zoomable image attachment",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .transformable(transformState)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = translation.x
                                translationY = translation.y
                            }
                    )
                } ?: Text("Loading image…", color = Color.White, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Text("CLOSE", color = Color.White)
                }
                if (scale > 1f) {
                    TextButton(
                        onClick = { scale = 1f; translation = Offset.Zero },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    ) { Text("RESET ZOOM", color = Color.White) }
                } else {
                    Text("PINCH TO ZOOM · DRAG TO MOVE", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
                }
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes <= 0L -> "Up to 10 MB"
    bytes < 1024L * 1024L -> "${(bytes / 1024.0).formatOneDecimal()} KB"
    else -> "${(bytes / (1024.0 * 1024.0)).formatOneDecimal()} MB"
}

private fun Double.formatOneDecimal(): String = String.format(Locale.getDefault(), "%.1f", this)
