package com.hybridmesh.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.PeerOrderingPolicy
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.messaging.model.ChatSummary
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun MessagesScreen(
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    onConversationClick: (String) -> Unit,
    onOpenDevices: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val viewModel: MessagesViewModel = viewModel()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val knownPeers by viewModel.knownPeers.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var nodeIdInput by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun copyNodeId(nodeId: String) {
        clipboard.setText(AnnotatedString(nodeId))
        Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MESSAGES", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Add a Node ID or start a conversation with a nearby node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RelayTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectedTabChange(0) },
                    text = { Text("CHATS", maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectedTabChange(1) },
                    text = { Text("NEARBY", maxLines = 1) }
                )
            }

            if (selectedTab == 0) ChatList(chats, onConversationClick, ::copyNodeId)
            else NearbyList(networkState, meshPeers, knownPeers, onConversationClick, onOpenDevices, ::copyNodeId)
        }
        if (selectedTab == 0) {
            FloatingActionButton(
                onClick = { nodeIdInput = ""; error = null; showAddDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = RelayAccent,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text(
                    "+",
                    modifier = Modifier.semantics { contentDescription = "Add a node" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("ADD NODE") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a Node ID to create its chat now. You can send messages while the node is away; they stay queued until it comes in range.")
                    TextField(
                        value = nodeIdInput,
                        onValueChange = { nodeIdInput = it.uppercase() },
                        singleLine = true,
                        label = { Text("Node ID") },
                        placeholder = { Text("HMR-xxxxxxxx-....") },
                        supportingText = { Text(error ?: "") },
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addNode(nodeIdInput) { ok, value ->
                        if (ok) showAddDialog = false else error = value
                    }
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun ChatList(chats: List<ChatSummary>, onConversationClick: (String) -> Unit, onCopyNodeId: (String) -> Unit) {
    if (chats.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(RelaySurface, MaterialTheme.shapes.large)
                    .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "NO CHATS YET",
                    style = TechnicalTextStyle,
                    color = RelayAccent,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Add a Node ID or open a nearby device to start a conversation.",
                    color = RelayTextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(chats, key = { it.peerNodeId }) { chat -> ChatRow(chat, onConversationClick, onCopyNodeId) }
    }
}

@Composable
private fun NearbyList(
    networkState: NetworkState,
    meshPeers: List<MeshPeer>,
    knownPeers: List<PeerEntity>,
    onConversationClick: (String) -> Unit,
    onOpenDevices: () -> Unit,
    onCopyNodeId: (String) -> Unit
) {
    val names = knownPeers.associateBy { it.nodeId.uppercase() }
    val blePeers = PeerOrderingPolicy.stableLive(networkState.peers).associateBy { it.nodeId.uppercase() }
    val nearby = meshPeers.map { meshPeer ->
        val blePeer = blePeers[meshPeer.nodeId.uppercase()]
        val savedName = names[meshPeer.nodeId.uppercase()]?.displayName
        val displayName = savedName?.takeIf {
            it.isNotBlank() && !it.equals(meshPeer.nodeId, true)
        } ?: meshPeer.deviceName.takeIf {
            it.isNotBlank() && !it.equals(meshPeer.nodeId, true)
        } ?: "Nearby node"
        val transports = buildList {
            if (blePeer != null || !meshPeer.bleAddress.isNullOrBlank()) add("BLE")
            if (!meshPeer.wifiHost.isNullOrBlank() && (meshPeer.wifiPort ?: 0) > 0) add("WI-FI DIRECT")
        }.joinToString(" · ")
        NearbyPeerItem(
            nodeId = meshPeer.nodeId,
            name = displayName,
            rssi = blePeer?.rssi ?: meshPeer.rssi,
            transportLabel = transports.ifBlank { "NEARBY" }
        )
    }.sortedBy { it.name.lowercase() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (networkState.scanningState != BleOperationState.ACTIVE && nearby.isEmpty()) {
            item {
                StatusCard(
                    title = "BLUETOOTH DISCOVERY UNAVAILABLE",
                    body = "Wi-Fi Direct can still discover nearby nodes. Devices contains Bluetooth recovery and permission controls.",
                    action = "OPEN DEVICES",
                    onAction = onOpenDevices
                )
            }
        }

        if (nearby.isEmpty()) {
            item { EmptyState("NO PEERS IN RANGE", "Only currently discovered Neyra nodes appear here.") }
        } else {
            items(nearby, key = { it.nodeId }) { peer ->
                PeerRow(
                    name = peer.name,
                    nodeId = peer.nodeId,
                    rssi = peer.rssi,
                    transportLabel = peer.transportLabel,
                    onCopyNodeId = onCopyNodeId
                ) {
                    onConversationClick(peer.nodeId)
                }
            }
        }
    }
}

private data class NearbyPeerItem(val nodeId: String, val name: String, val rssi: Int, val transportLabel: String)

@Composable
private fun PeerRow(name: String, nodeId: String, rssi: Int, transportLabel: String, onCopyNodeId: (String) -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RelaySurface)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).clickable(onClick = onClick).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(RelayAccent, CircleShape))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onCopyNodeId(nodeId) })
            }
            Text("$rssi dBm", style = TechnicalTextStyle, color = RelayTextMuted)
        }

        Text("IN RANGE • $transportLabel", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onConversationClick: (String) -> Unit, onCopyNodeId: (String) -> Unit) {
    val status = when (chat.lastStatus) {
        DeliveryStatus.DELIVERED -> "DELIVERED"
        DeliveryStatus.QUEUED -> "QUEUED"
        DeliveryStatus.IN_FLIGHT -> "SENDING"
        DeliveryStatus.FAILED -> "FAILED"
        null -> ""
    }

    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RelaySurface)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
            .clickable { onConversationClick(chat.peerNodeId) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(chat.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(chat.peerNodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onCopyNodeId(chat.peerNodeId) })
            val preview = chat.lastMessage?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: "New contact · messages wait until this node is nearby"
            Text(preview, style = MaterialTheme.typography.bodySmall, color = RelayTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) Text(status, style = TechnicalTextStyle, color = RelayTextMuted)
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RelaySurface)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(body)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayAccent, fontWeight = FontWeight.Bold)
        Text(body, color = RelayTextMuted, textAlign = TextAlign.Center)
    }
}
