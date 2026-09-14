package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.messaging.model.ChatSummary
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class MessagesViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val identityStore = IdentityStore.getInstance(application)
    private val repository = MessagingRepository.getInstance(application)
    private val networkManager = NetworkManager.getInstance(application)
    val identity = identityStore.identity
    val networkState: StateFlow<NetworkState> = networkManager.state

    val messages: StateFlow<List<MessageRecordEntity>> =
        repository.allMessages.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList()
        )

    val knownPeers: StateFlow<List<PeerEntity>> =
        repository.knownPeers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList()
        )

    val chats: StateFlow<List<ChatSummary>> =
        combine(messages, knownPeers, identity) { rows, peers, localIdentity ->
            buildChats(rows, peers, localIdentity.nodeId)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList()
        )


    val sentCount: StateFlow<Int> =
        combine(messages, identity) { rows, localIdentity ->
            rows.count {
                it.senderNodeId.equals(
                    localIdentity.nodeId,
                    ignoreCase = true
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            0
        )

    val receivedCount: StateFlow<Int> =
        combine(messages, identity) { rows, localIdentity ->
            rows.count {
                !it.senderNodeId.equals(
                    localIdentity.nodeId,
                    ignoreCase = true
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            0
        )

    val queuedCount: StateFlow<Int> =
        messages.map { rows ->
            rows.count {
                it.status == DeliveryStatus.QUEUED.name ||
                    it.status == DeliveryStatus.IN_FLIGHT.name
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            0
        )

    val localNodeId: String
        get() = identityStore.getIdentity().nodeId

    fun observeConversation(
        peerNodeId: String
    ): Flow<List<MessageRecordEntity>> {
        val normalizedPeerId = peerNodeId.trim().uppercase(Locale.US)
        val normalizedLocalId = identityStore.getIdentity().nodeId.trim().uppercase(Locale.US)

        return messages.map { rows ->
            rows.filter { message ->
                val sender = message.senderNodeId.trim().uppercase(Locale.US)
                val recipient = message.recipientNodeId.trim().uppercase(Locale.US)

                (sender == normalizedLocalId && recipient == normalizedPeerId) ||
                    (sender == normalizedPeerId && recipient == normalizedLocalId)
            }.sortedBy { it.createdAt }
        }
    }

    fun addNode(
        nodeIdInput: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val nodeId = nodeIdInput
            .trim()
            .uppercase(Locale.US)

        viewModelScope.launch {
            when {
                !NodeIdGenerator.isValid(nodeId) ->
                    onResult(false, "Enter a valid Node ID.")

                nodeId.equals(localNodeId, ignoreCase = true) ->
                    onResult(
                        false,
                        "You cannot add this device as a recipient."
                    )

                else -> {
                    repository.ensurePeer(nodeId)
                    onResult(true, nodeId)
                }
            }
        }
    }

    fun send(
        peerNodeId: String,
        content: String,
        type: MessageType,
        onDone: () -> Unit = {}
    ) {
        val nodeId = peerNodeId
            .trim()
            .uppercase(Locale.US)
        val message = content.trim()

        if (
            message.isBlank() ||
            !NodeIdGenerator.isValid(nodeId) ||
            nodeId.equals(localNodeId, ignoreCase = true)
        ) return

        viewModelScope.launch {
            repository.addOutgoing(
                message,
                nodeId,
                type.name
            )
            onDone()
        }
    }

    fun sendLocation(
        peerNodeId: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        timestamp: Long
    ) {
        val nodeId = peerNodeId.trim().uppercase(Locale.US)
        if (!NodeIdGenerator.isValid(nodeId) || nodeId.equals(localNodeId, ignoreCase = true)) return
        if (!latitude.isFinite() || !longitude.isFinite()) return

        viewModelScope.launch {
            repository.addOutgoing(
                content = LocationPayload(latitude, longitude, accuracyMeters, timestamp).encode(),
                recipientNodeId = nodeId,
                messageType = MessageType.LOCATION.name
            )
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun deleteConversation(peerNodeId: String) {
        val nodeId = peerNodeId
            .trim()
            .uppercase(Locale.US)
        if (nodeId.isBlank()) return

        viewModelScope.launch {
            repository.deleteConversation(nodeId)
        }
    }

    private fun buildChats(
        messageRows: List<MessageRecordEntity>,
        peerRows: List<PeerEntity>,
        localNodeId: String
    ): List<ChatSummary> {
        val peerMap = peerRows.associateBy {
            it.nodeId.uppercase(Locale.US)
        }

        val groups =
            linkedMapOf<String, MutableList<MessageRecordEntity>>()

        // A conversation exists only when an actual message exists.
        messageRows.forEach { message ->
            val sender =
                message.senderNodeId.uppercase(Locale.US)
            val recipient =
                message.recipientNodeId.uppercase(Locale.US)

            val peerId =
                if (sender.equals(localNodeId, ignoreCase = true)) {
                    recipient
                } else {
                    sender
                }

            groups
                .getOrPut(peerId) { mutableListOf() }
                .add(message)
        }

        return groups
            .map { (peerId, rows) ->
                val peer = peerMap[peerId]
                val last = rows.maxByOrNull { it.createdAt }

                ChatSummary(
                    peerNodeId = peerId,
                    displayName = peer?.displayName
                        ?.takeIf {
                            it.isNotBlank() &&
                                !it.equals(peerId, ignoreCase = true)
                        }
                        ?: peerId,
                    lastMessage = last?.content,
                    lastActivity = last?.createdAt ?: 0L,
                    lastStatus = last?.let { message ->
                        DeliveryStatus.entries.firstOrNull {
                            it.name == message.status
                        }
                    }
                )
            }
            .sortedByDescending { it.lastActivity }
    }
}
