package com.hybridmesh.relay.messaging.data

import android.content.Context
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class MessagingRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val identityStore = IdentityStore.getInstance(appContext)
    private val database = MessagingDatabase.getInstance(appContext)
    private val peers = database.peerDao()
    private val messages = database.messageDao()

    val allMessages: Flow<List<MessageRecordEntity>> = messages.observeAll()

    val knownPeers: Flow<List<PeerEntity>> = peers.observeAll()


    fun observePendingCount(): Flow<Int> =
        messages.observePendingCount(identityStore.getIdentity().nodeId)

    fun observeQueuedOutgoing(): Flow<List<MessageRecordEntity>> =
        messages.observeQueuedOutgoing(identityStore.getIdentity().nodeId)

    suspend fun upsertPeer(peer: PeerEntity) = peers.upsert(peer)

    suspend fun getPeer(nodeId: String): PeerEntity? = peers.get(nodeId.trim().uppercase())

    suspend fun ensurePeer(nodeId: String) {
        val normalized = nodeId.trim().uppercase()
        val localNodeId = identityStore.getIdentity().nodeId
        if (normalized.equals(localNodeId, ignoreCase = true)) return

        if (peers.get(normalized) == null) {
            peers.upsert(
                PeerEntity(
                    nodeId = normalized,
                    displayName = normalized,
                    deviceType = "PHONE",
                    address = null,
                    lastRssi = null,
                    lastSeenAt = null
                )
            )
        }
    }

    suspend fun updateDiscoveredPeer(peer: BlePeer) {
        if (peer.nodeId.equals(identityStore.getIdentity().nodeId, ignoreCase = true)) {
            return
        }

        val existing = peers.get(peer.nodeId)
        val advertisedName = peer.deviceName.trim()
        val name = advertisedName
            .takeIf { it.isNotBlank() && !it.equals(peer.nodeId, ignoreCase = true) }
            ?: existing?.displayName
            ?: peer.nodeId

        peers.upsert(
            PeerEntity(
                nodeId = peer.nodeId,
                displayName = name,
                deviceType = peer.deviceType.name,
                address = peer.address.takeIf { it != "Unknown" } ?: existing?.address,
                lastRssi = peer.rssi,
                lastSeenAt = peer.lastSeen
            )
        )
    }

    suspend fun updatePeerIdentity(
        nodeId: String,
        displayName: String,
        deviceType: String
    ) {
        val normalized = nodeId.trim().uppercase()
        val existing = peers.get(normalized)

        peers.upsert(
            PeerEntity(
                nodeId = normalized,
                displayName = displayName.trim().takeIf { it.isNotBlank() } ?: existing?.displayName ?: normalized,
                deviceType = deviceType,
                address = existing?.address,
                lastRssi = existing?.lastRssi,
                lastSeenAt = existing?.lastSeenAt
            )
        )
    }

    suspend fun addIncoming(record: MessageRecordEntity) {
        require(!record.senderNodeId.equals(identityStore.getIdentity().nodeId, ignoreCase = true))
        messages.insert(record)
        ensurePeer(record.senderNodeId)
    }

    suspend fun addOutgoing(
        content: String,
        recipientNodeId: String,
        messageType: String
    ): MessageRecordEntity {
        val cleanRecipient = recipientNodeId.trim().uppercase()
        require(content.isNotBlank())
        require(NodeIdGenerator.isValid(cleanRecipient))
        require(!cleanRecipient.equals(identityStore.getIdentity().nodeId, ignoreCase = true))

        val token = UUID.randomUUID()
        val messageId = "HMRM-${identityStore.getIdentity().nodeId.removePrefix("HMR-").lowercase()}::$token"
        val now = System.currentTimeMillis()

        val record = MessageRecordEntity(
            messageId = messageId,
            senderNodeId = identityStore.getIdentity().nodeId,
            recipientNodeId = cleanRecipient,
            content = content.trim(),
            createdAt = now,
            messageType = messageType,
            status = DeliveryStatus.QUEUED.name,
            lastTransport = null,
            deliveredAt = null,
            attemptCount = 0,
            nextAttemptAt = now,
            lastError = null
        )

        messages.insert(record)
        ensurePeer(cleanRecipient)
        return record
    }

    suspend fun claimForDelivery(messageId: String): Boolean =
        messages.claimForDelivery(messageId) == 1

    suspend fun markDelivered(
        messageId: String,
        transport: String,
        deliveredAt: Long = System.currentTimeMillis()
    ) {
        messages.updateStatus(
            messageId = messageId,
            status = DeliveryStatus.DELIVERED.name,
            transport = transport,
            deliveredAt = deliveredAt,
            nextAttemptAt = null,
            lastError = null
        )
    }

    suspend fun markFailed(
        messageId: String,
        transport: String?,
        error: String
    ) {
        messages.updateStatus(
            messageId = messageId,
            status = DeliveryStatus.FAILED.name,
            transport = transport,
            deliveredAt = null,
            nextAttemptAt = null,
            lastError = error
        )
    }

    suspend fun scheduleRetry(
        messageId: String,
        attemptCount: Int,
        nextAttemptAt: Long,
        error: String
    ) {
        messages.scheduleRetry(
            messageId = messageId,
            transport = "BLE",
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            lastError = error
        )
    }

    suspend fun resetInFlightMessages() {
        val now = System.currentTimeMillis()
        messages.resetInFlight(
            localNodeId = identityStore.getIdentity().nodeId,
            now = now,
            lastError = "SERVICE_RESTARTED"
        )
    }

    suspend fun makeRecipientEligible(peerNodeId: String) {
        messages.makeRecipientEligible(
            localNodeId = identityStore.getIdentity().nodeId,
            peerNodeId = peerNodeId.trim().uppercase(),
            now = System.currentTimeMillis()
        )
    }

    suspend fun makeAllQueuedEligible() {
        messages.makeAllQueuedEligible(
            localNodeId = identityStore.getIdentity().nodeId,
            now = System.currentTimeMillis()
        )
    }

    suspend fun getQueuedOutgoing(): List<MessageRecordEntity> =
        messages.getQueuedOutgoing(identityStore.getIdentity().nodeId)

    suspend fun getEligibleOutgoing(now: Long = System.currentTimeMillis()): List<MessageRecordEntity> =
        messages.getEligibleOutgoing(identityStore.getIdentity().nodeId, now)

    suspend fun getEarliestNextAttemptAt(): Long? =
        messages.getEarliestNextAttemptAt(identityStore.getIdentity().nodeId)

    suspend fun getById(messageId: String): MessageRecordEntity? = messages.getById(messageId)

    suspend fun deleteMessage(messageId: String) = messages.delete(messageId)

    suspend fun deleteConversation(peerNodeId: String) =
        messages.deleteConversation(
            identityStore.getIdentity().nodeId,
            peerNodeId.trim().uppercase()
        )

    companion object {
        @Volatile
        private var INSTANCE: MessagingRepository? = null

        fun getInstance(context: Context): MessagingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessagingRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
