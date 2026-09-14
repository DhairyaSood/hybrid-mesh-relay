package com.hybridmesh.relay.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.PeerOrderingPolicy
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.messaging.model.ChatSummary
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel

@Composable
fun MessagesScreen(onConversationClick: (String) -> Unit, onOpenDevices: () -> Unit = {}) {
    val viewModel: MessagesViewModel = viewModel()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val knownPeers by viewModel.knownPeers.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var nodeIdInput by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("MESSAGES", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Chats are created only by actual messages.", style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
                }
                if (selectedTab == 0) {
                    TextButton(onClick = { nodeIdInput = ""; error = null; showAddDialog = true }) { Text("ADD NODE") }
                }
            }
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("CHATS", maxLines = 1) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("NEARBY", maxLines = 1) })
            }
            if (selectedTab == 0) ChatList(chats, onConversationClick)
            else NearbyList(networkState, knownPeers, onConversationClick, onOpenDevices)
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("ADD NODE") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save a Node ID without creating a chat. A chat appears only after a real message is exchanged.")
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
private fun ChatList(chats: List<ChatSummary>, onConversationClick: (String) -> Unit) {
    if (chats.isEmpty()) {
        EmptyState("NO CHATS YET", "Send or receive a message to create your first conversation.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(chats, key = { it.peerNodeId }) { chat -> ChatRow(chat, onConversationClick) }
    }
}

@Composable
private fun NearbyList(
    networkState: NetworkState,
    knownPeers: List<PeerEntity>,
    onConversationClick: (String) -> Unit,
    onOpenDevices: () -> Unit
) {
    val names = knownPeers.associateBy { it.nodeId.uppercase() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (networkState.scanningState != BleOperationState.ACTIVE) {
            item {
                StatusCard(
                    title = "DISCOVERY NOT ACTIVE",
                    body = "The mesh service owns discovery. Devices contains the recovery and permission controls.",
                    action = "OPEN DEVICES",
                    onAction = onOpenDevices
                )
            }
        }
        if (networkState.peers.isEmpty()) {
            item { EmptyState("NO PEERS IN RANGE", "Only currently discovered Neyra nodes appear here.") }
        } else {
            items(PeerOrderingPolicy.stableLive(networkState.peers), key = { it.nodeId }) { peer ->
                val name = names[peer.nodeId.uppercase()]?.displayName
                    ?.takeIf { it.isNotBlank() && !it.equals(peer.nodeId, true) }
                    ?: peer.deviceName
                PeerRow(name = name, nodeId = peer.nodeId, rssi = peer.rssi, type = peer.deviceType.name) {
                    onConversationClick(peer.nodeId)
                }
            }
        }
    }
}

@Composable
private fun PeerRow(name: String, nodeId: String, rssi: Int, type: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RelaySurface)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).clickable(onClick = onClick).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(RelayAccent, CircleShape))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(nodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1)
            }
            Text("$rssi dBm", style = TechnicalTextStyle, color = RelayTextMuted)
        }
        Text("$type • IN RANGE • BLE DISCOVERED", style = TechnicalTextStyle, color = RelayTextMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onConversationClick: (String) -> Unit) {
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
            Text(chat.peerNodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1)
        }
        if (status.isNotBlank()) Text(status, style = TechnicalTextStyle, color = RelayTextMuted)
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RelaySurface).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(body)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = TechnicalTextStyle, color = RelayAccent, fontWeight = FontWeight.Bold)
        Text(body, color = RelayTextMuted)
    }
}
