package com.hybridmesh.relay.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
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
import com.hybridmesh.relay.location.LocationActions
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferStatus
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferError
import com.hybridmesh.relay.messaging.attachment.AttachmentExport
import com.hybridmesh.relay.messaging.attachment.AttachmentExportResult
import com.hybridmesh.relay.messaging.attachment.AttachmentExportSource
import com.hybridmesh.relay.ui.components.NodeIdText
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.BleRuntimeState
import com.hybridmesh.relay.network.connectionQuality
import com.hybridmesh.relay.notifications.MessagingNotificationCoordinator
import com.hybridmesh.relay.permissions.LocationPermissionLevel
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
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
    val diagnostics by viewModel.networkDiagnostics.collectAsStateWithLifecycle()
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
    var selectedVideo by remember { mutableStateOf<AttachmentExportSource?>(null) }
    var selectedImage by remember { mutableStateOf<AttachmentExportSource?>(null) }
    var pendingLegacyExport by remember { mutableStateOf<AttachmentExportSource?>(null) }
    var exportingAttachmentPath by remember { mutableStateOf<String?>(null) }
    var selectedLocation by remember { mutableStateOf<LocationPayload?>(null) }
    var locationActionError by remember { mutableStateOf<String?>(null) }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            attachmentError = null
            viewModel.sendAttachment(peerNodeId, uri) { success, error ->
                if (!success) attachmentError = error ?: "Could not add this attachment."
            }
        }
    }

    val legacyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val source = pendingLegacyExport
        pendingLegacyExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        exportingAttachmentPath = source.file.absolutePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AttachmentExport.exportToUri(context, source, uri)
            }
            exportingAttachmentPath = null
            when (result) {
                is AttachmentExportResult.Success ->
                    Toast.makeText(context, "Saved ${result.displayName}", Toast.LENGTH_LONG).show()
                is AttachmentExportResult.Failure ->
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportAttachment(source: AttachmentExportSource) {
        if (exportingAttachmentPath != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportingAttachmentPath = source.file.absolutePath
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    AttachmentExport.exportToDownloads(context, source)
                }
                exportingAttachmentPath = null
                when (result) {
                    is AttachmentExportResult.Success ->
                        Toast.makeText(context, "Saved ${result.displayName} to Downloads/Neyra", Toast.LENGTH_LONG).show()
                    is AttachmentExportResult.Failure ->
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            pendingLegacyExport = source
            legacyExportLauncher.launch(AttachmentExport.suggestedFileName(source))
        }
    }

    val peer = peers.firstOrNull { it.nodeId.equals(peerNodeId, true) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() && !it.equals(peerNodeId, true) } ?: "Unknown node"
    val nearbyPeer = meshPeers.firstOrNull { it.nodeId.equals(peerNodeId, true) }
    val inRange = nearbyPeer?.hasAnyEndpoint() == true
    val wifiHealth = nearbyPeer
        ?.takeIf { !it.wifiHost.isNullOrBlank() }
        ?.let { diagnostics.peerHealth(it.nodeId, "WIFI_DIRECT") }
    val connectionQuality = connectionQuality(
        bleRssi = networkState.peers.firstOrNull { it.nodeId.equals(peerNodeId, true) }?.rssi,
        wifiHealth = wifiHealth
    )

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

                NodeIdText(
                    peerNodeId,
                    style = MaterialTheme.typography.labelSmall,
                    color = RelayAccent,
                )

                connectionQuality?.let { quality ->
                    val hasBle = networkState.peers.any { it.nodeId.equals(peerNodeId, true) }
                    val hasWifi = !nearbyPeer?.wifiHost.isNullOrBlank()
                    val sourceLabel = when {
                        hasBle && hasWifi -> "CONNECTION"
                        hasWifi -> "WI-FI DIRECT"
                        else -> "BLE"
                    }
                    Text(
                        "$sourceLabel · ${quality.label}${if (!hasBle || !hasWifi) " · ${quality.detail}" else ""}",
                        color = RelayTextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

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
                                onLongPress = {
                                    val location = LocationPayload.decode(message.content)
                                    if (message.messageType == MessageType.LOCATION.name && location != null) {
                                        selectedLocation = location
                                    } else {
                                        diagnosticsMessageId = message.messageId
                                    }
                                },
                                onOpenVideo = { selectedVideo = it },
                                onOpenImage = { selectedImage = it }
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
                                onClick = {
                                    val mediaIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                                    }
                                    attachmentPicker.launch(
                                        Intent.createChooser(mediaIntent, "Choose a media app")
                                    )
                                },
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
            }
        }
    }

    diagnosticsMessageId?.let { messageId ->
        val selectedMessage = messages.firstOrNull { it.messageId == messageId }
        val traceEvents by viewModel.observeTraceEvents(messageId).collectAsStateWithLifecycle(initialValue = emptyList())
        if (selectedMessage != null) {
            MessageDiagnosticsDialog(
                message = selectedMessage,
                attachment = attachments.firstOrNull { it.messageId == messageId },
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

    selectedLocation?.let { payload ->
        AlertDialog(
            onDismissRequest = { selectedLocation = null },
            title = { Text("LOCATION ACTIONS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${LocationPayload.formatCoordinate(payload.latitude)}, " +
                            LocationPayload.formatCoordinate(payload.longitude),
                        style = TechnicalTextStyle
                    )
                    payload.accuracyMeters?.let {
                        Text("Accuracy ±${it.toInt()} m", color = RelayTextMuted)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!LocationActions.openInMaps(context, payload)) {
                            locationActionError = "No browser or Google Maps app can open this location."
                        }
                        selectedLocation = null
                    }
                ) { Text("OPEN IN GOOGLE MAPS") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                "${payload.latitude},${payload.longitude}"
                            )
                        )
                        selectedLocation = null
                    }) { Text("COPY COORDINATES") }
                    TextButton(onClick = { selectedLocation = null }) { Text("CANCEL") }
                }
            }
        )
    }

    locationActionError?.let { error ->
        AlertDialog(
            onDismissRequest = { locationActionError = null },
            title = { Text("LOCATION UNAVAILABLE") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { locationActionError = null }) { Text("OK") }
            }
        )
    }

    attachmentError?.let { error ->
        AlertDialog(
            onDismissRequest = { attachmentError = null },
            title = { Text("CAN’T SEND THIS FILE") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { attachmentError = null }) { Text("OK") }
            }
        )
    }

    selectedImage?.let { source ->
        AttachmentImageViewer(
            source = source,
            exporting = exportingAttachmentPath == source.file.absolutePath,
            onDownload = { exportAttachment(source) },
            onDismiss = { selectedImage = null }
        )
    }

    selectedVideo?.let { source ->
        val path = source.file.absolutePath
        var player by remember(path) { mutableStateOf<VideoView?>(null) }
        var prepared by remember(path) { mutableStateOf(false) }
        var playing by remember(path) { mutableStateOf(false) }
        var durationMs by remember(path) { mutableStateOf(0) }
        var positionMs by remember(path) { mutableStateOf(0) }
        var bufferedPercent by remember(path) { mutableStateOf(0) }
        var seekPosition by remember(path) { mutableStateOf<Float?>(null) }
        var playbackError by remember(path) { mutableStateOf<String?>(null) }
        DisposableEffect(path) {
            onDispose {
                player?.stopPlayback()
                player = null
            }
        }
        LaunchedEffect(path, player, prepared) {
            while (player != null && prepared) {
                val activePlayer = player ?: break
                if (seekPosition == null) {
                    positionMs = activePlayer.currentPosition.coerceAtLeast(0)
                }
                playing = activePlayer.isPlaying
                bufferedPercent = activePlayer.bufferPercentage.coerceIn(0, 100)
                kotlinx.coroutines.delay(250L)
            }
        }
        Dialog(
            onDismissRequest = { selectedVideo = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { playerContext ->
                            VideoView(playerContext).apply {
                                player = this
                                setVideoPath(path)
                                setOnPreparedListener { mediaPlayer ->
                                    prepared = true
                                    durationMs = mediaPlayer.duration.coerceAtLeast(0)
                                    mediaPlayer.start()
                                    playing = true
                                }
                                setOnCompletionListener { playing = false; positionMs = durationMs }
                                setOnErrorListener { _, what, extra ->
                                    playbackError = "Playback error $what/$extra"
                                    prepared = false
                                    playing = false
                                    true
                                }
                            }
                        }
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        color = Color.Black.copy(alpha = 0.78f)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (playbackError != null) {
                                Text(playbackError!!, color = MaterialTheme.colorScheme.error)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val activePlayer = player ?: return@IconButton
                                        if (activePlayer.isPlaying) {
                                            activePlayer.pause()
                                            playing = false
                                        } else {
                                            activePlayer.start()
                                            playing = true
                                        }
                                    },
                                    enabled = prepared
                                ) {
                                    Icon(
                                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (playing) "Pause video" else "Play video",
                                        tint = Color.White
                                    )
                                }
                                Slider(
                                    value = seekPosition ?: positionMs.toFloat(),
                                    onValueChange = { value ->
                                        seekPosition = value
                                        positionMs = value.toInt()
                                    },
                                    onValueChangeFinished = {
                                        seekPosition?.let { player?.seekTo(it.toInt()) }
                                        seekPosition = null
                                    },
                                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                                    enabled = prepared && durationMs > 0,
                                        modifier = Modifier.weight(1f)
                                    )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatVideoTime(seekPosition?.toInt() ?: positionMs), color = Color.White, style = TechnicalTextStyle)
                                Text(
                                    if (prepared) "${bufferedPercent}% buffered · ${formatVideoTime(durationMs)}"
                                    else "Preparing video…",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(
                                onClick = { exportAttachment(source) },
                                enabled = exportingAttachmentPath != path,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = "Download video",
                                    tint = Color.White.copy(
                                        alpha = if (exportingAttachmentPath == path) 0.45f else 1f
                                    )
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { selectedVideo = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) { Text("CLOSE", color = Color.White) }
                }
            }

        }
    }
}

private fun formatVideoTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
    onOpenVideo: (AttachmentExportSource) -> Unit,
    onOpenImage: (AttachmentExportSource) -> Unit
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
                    val exportSource = AttachmentExportSource(
                        file = java.io.File(localPath),
                        mimeType = descriptor.mimeType,
                        displayName = attachment?.displayName ?: descriptor.displayName,
                        sizeBytes = descriptor.sizeBytes
                    )
                    if (isVideo) {
                        AttachmentVideoPreview(localPath, onClick = { onOpenVideo(exportSource) })
                    } else {
                        AttachmentImagePreview(localPath, onClick = { onOpenImage(exportSource) })
                    }
                }
                if (!isVideo || localPath == null) {
                    Text(if (isVideo) "VIDEO" else "IMAGE", style = MaterialTheme.typography.labelSmall, color = RelayAccent, fontWeight = FontWeight.Bold)
                    Text(attachment?.displayName ?: descriptor.displayName, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(formatAttachmentSize(descriptor.sizeBytes), style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
                val state = attachment?.status ?: AttachmentTransferStatus.WAITING_WIFI
                val progress = attachment?.transferredBytes ?: 0L
                val needsWifiForLargeFile = attachment?.lastError ==
                    AttachmentTransferError.WIFI_DIRECT_REQUIRED_FOR_LARGE_ATTACHMENT
                Text(
                    when {
                        needsWifiForLargeFile && state == AttachmentTransferStatus.WAITING_WIFI -> "WAITING · WI-FI DIRECT REQUIRED"
                        needsWifiForLargeFile -> "NOT SENT · WI-FI DIRECT REQUIRED"
                        state == AttachmentTransferStatus.DELIVERED -> if (attachment?.localPath != null) "AVAILABLE" else "DELIVERED"
                        state == AttachmentTransferStatus.SENDING -> "SENDING · ${formatAttachmentSize(progress)}"
                        state == AttachmentTransferStatus.RECEIVING -> "RECEIVING · ${formatAttachmentSize(progress)}"
                        state == AttachmentTransferStatus.RELAYED -> "FORWARDED · AWAITING RECIPIENT"
                        state == AttachmentTransferStatus.RELAY_QUEUED -> "WAITING FOR RELAY LINK"
                        state == AttachmentTransferStatus.FAILED -> "TRANSFER FAILED"
                        state == AttachmentTransferStatus.QUEUED || state == AttachmentTransferStatus.WAITING_WIFI -> "WAITING FOR A COMPATIBLE LINK"
                        else -> "WAITING FOR A COMPATIBLE LINK"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == AttachmentTransferStatus.FAILED) MaterialTheme.colorScheme.error else RelayTextMuted
                )
                if (needsWifiForLargeFile) {
                    Text(
                        if (state == AttachmentTransferStatus.WAITING_WIFI) {
                            "Connect a Wi-Fi Direct peer; this queued transfer will resume automatically. BLE supports files up to 5 MB."
                        } else {
                            "Connect a Wi-Fi Direct peer, then select the file again. BLE supports files up to 5 MB."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
private fun AttachmentImageViewer(
    source: AttachmentExportSource,
    exporting: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val path = source.file.absolutePath
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
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDownload, enabled = !exporting) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download photo",
                            tint = Color.White.copy(alpha = if (exporting) 0.45f else 1f)
                        )
                    }
                    if (scale > 1f) {
                        TextButton(onClick = { scale = 1f; translation = Offset.Zero }) {
                            Text("RESET ZOOM", color = Color.White)
                        }
                    } else {
                        Text("PINCH TO ZOOM · DRAG TO MOVE", color = Color.White)
                    }
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
