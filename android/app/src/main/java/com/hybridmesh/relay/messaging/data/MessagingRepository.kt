package com.hybridmesh.relay.messaging.data

import android.content.Context
import androidx.room.withTransaction
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.ble.BleConstants
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.attachment.AttachmentDescriptor
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferStatus
import com.hybridmesh.relay.messaging.attachment.AttachmentFileStore
import com.hybridmesh.relay.model.MessageType
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow

class MessagingRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val identityStore = IdentityStore.getInstance(appContext)
    private val database = MessagingDatabase.getInstance(appContext)
    private val peers = database.peerDao()
    private val messages = database.messageDao()
    private val forwarding = database.meshForwardingDao()
    private val seenPackets = database.meshSeenPacketDao()
    private val traceEvents = database.messageTraceEventDao()
    private val attachments = database.attachmentDao()
    private val tracePurgeCounter = AtomicInteger(0)

    val allMessages: Flow<List<MessageRecordEntity>> = messages.observeAll()

    val knownPeers: Flow<List<PeerEntity>> = peers.observeAll()

    fun observeTraceEvents(messageId: String): Flow<List<MessageTraceEventEntity>> =
        traceEvents.observeForMessage(messageId)

    fun observeAttachmentsForConversation(peerNodeId: String): Flow<List<AttachmentRecordEntity>> =
        attachments.observeConversation(identityStore.getIdentity().nodeId, normalizeNodeId(peerNodeId))

    fun observeAllAttachments(): Flow<List<AttachmentRecordEntity>> = attachments.observeAll()

    suspend fun getAttachment(messageId: String): AttachmentRecordEntity? = attachments.get(messageId)

    suspend fun getPendingOutgoingAttachments(limit: Int = 8): List<AttachmentRecordEntity> =
        attachments.getPendingOutgoing(identityStore.getIdentity().nodeId, limit)

    suspend fun updateAttachmentProgress(
        messageId: String,
        status: String,
        transferredBytes: Long,
        error: String? = null
    ) {
        attachments.updateProgress(messageId, status, transferredBytes.coerceAtLeast(0L), System.currentTimeMillis(), error)
    }

    suspend fun resetInterruptedAttachmentTransfers() {
        attachments.resetInterruptedTransfers(System.currentTimeMillis())
    }

    suspend fun recordTraceEvent(
        messageId: String,
        eventType: String,
        packetId: String? = null,
        transport: String? = null,
        peerNodeId: String? = null,
        attemptNumber: Int? = null,
        durationMs: Long? = null,
        resultCode: String? = null,
        detail: String? = null,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        if (messageId.isBlank()) return
        traceEvents.insert(
            MessageTraceEventEntity(
                messageId = messageId,
                packetId = packetId,
                occurredAt = occurredAt,
                eventType = eventType.take(48),
                transport = transport?.take(32),
                peerNodeId = peerNodeId?.take(128),
                attemptNumber = attemptNumber,
                durationMs = durationMs,
                resultCode = resultCode?.take(96),
                detail = detail?.take(256)
            )
        )
        // Bounded local history; no payload/body is ever written to trace events.
        if (tracePurgeCounter.incrementAndGet() % 64 == 0) {
            traceEvents.deleteOlderThan(occurredAt - 7L * 24 * 60 * 60 * 1000)
        }
    }

    suspend fun updateRouteTrace(messageId: String, routeTrace: String, complete: Boolean = true) {
        messages.updateRouteTrace(messageId, routeTrace.take(32), complete)
    }

    fun observePendingCount(): Flow<Int> =
        messages.observePendingCount(identityStore.getIdentity().nodeId)

    fun observeQueuedOutgoing(): Flow<List<MessageRecordEntity>> =
        messages.observeQueuedOutgoing(identityStore.getIdentity().nodeId)

    private fun normalizeNodeId(nodeId: String): String =
        nodeId.trim().lowercase().replaceFirst("hmr-", "HMR-")

    suspend fun upsertPeer(peer: PeerEntity) = peers.upsert(peer)

    suspend fun getPeer(nodeId: String): PeerEntity? = peers.get(normalizeNodeId(nodeId))

    suspend fun ensurePeer(nodeId: String) {
        val normalized = normalizeNodeId(nodeId)
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
                    lastSeenAt = null,
                    meshProtocolVersion = 0,
                    canRelay = false,
                    canStoreForward = false
                )
            )
        }
    }

    suspend fun savePeerForChat(nodeId: String) {
        val normalized = normalizeNodeId(nodeId)
        ensurePeer(normalized)
        peers.saveForChat(normalized)
    }

    suspend fun updateDiscoveredPeer(peer: BlePeer) {
        val normalized = normalizeNodeId(peer.nodeId)

        if (normalized.equals(identityStore.getIdentity().nodeId, ignoreCase = true)) {
            return
        }

        val existing = peers.get(normalized)
        val advertisedName = peer.deviceName.trim()
        val previousName = existing?.displayName.orEmpty()
        // BLE's compact advertisement carries only the first six UTF-8 bytes.
        // Preserve the longer verified identity instead of replacing it on every scan.
        val isAdvertisementPrefixOfKnownName = advertisedName.isNotBlank() &&
            advertisedName.length == BleConstants.MESH_DISCOVERY_NAME_MAX_BYTES &&
            previousName.length > advertisedName.length &&
            previousName.startsWith(advertisedName, ignoreCase = true)
        val name = when {
            isAdvertisementPrefixOfKnownName -> previousName
            advertisedName.isNotBlank() && !advertisedName.equals(normalized, ignoreCase = true) -> advertisedName
            previousName.isNotBlank() -> previousName
            else -> normalized
        }

        peers.upsert(
            PeerEntity(
                nodeId = normalized,
                displayName = name,
                deviceType = peer.deviceType.name,
                address = peer.address.takeIf { it != "Unknown" } ?: existing?.address,
                lastRssi = peer.rssi,
                lastSeenAt = peer.lastSeen,
                meshProtocolVersion = peer.meshProtocolVersion,
                canRelay = peer.canRelay,
                canStoreForward = peer.canStoreForward,
                isSavedForChat = existing?.isSavedForChat ?: false
            )
        )
    }

    /** Upserts a Wi-Fi-discovered peer without overwriting its BLE address field. */
    suspend fun updateWifiDiscoveredPeer(
        nodeId: String,
        displayName: String,
        protocolVersion: Int,
        canRelay: Boolean,
        canStoreForward: Boolean,
        observedAt: Long = System.currentTimeMillis()
    ) {
        val normalized = normalizeNodeId(nodeId)
        if (normalized.isBlank() || normalized.equals(identityStore.getIdentity().nodeId, true)) return
        val existing = peers.get(normalized)
        val advertisedName = displayName.trim()
        val name = advertisedName
            .takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
            ?: existing?.displayName
            ?: normalized
        peers.upsert(
            PeerEntity(
                nodeId = normalized,
                displayName = name,
                deviceType = existing?.deviceType ?: "PHONE",
                address = existing?.address,
                lastRssi = existing?.lastRssi,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observedAt),
                meshProtocolVersion = maxOf(existing?.meshProtocolVersion ?: 0, protocolVersion),
                canRelay = canRelay || (existing?.canRelay == true),
                canStoreForward = canStoreForward || (existing?.canStoreForward == true),
                isSavedForChat = existing?.isSavedForChat ?: false
            )
        )
        makeRecipientEligible(normalized)
        makeAllQueuedEligible()
        makePendingForwardingEligible()
    }

    suspend fun updatePeerIdentity(
        nodeId: String,
        displayName: String,
        deviceType: String
    ) {
        val normalized = normalizeNodeId(nodeId)
        val existing = peers.get(normalized)

        peers.upsert(
            PeerEntity(
                nodeId = normalized,
                displayName = displayName.trim().takeIf { it.isNotBlank() } ?: existing?.displayName ?: normalized,
                deviceType = deviceType,
                address = existing?.address,
                lastRssi = existing?.lastRssi,
                lastSeenAt = existing?.lastSeenAt,
                meshProtocolVersion = existing?.meshProtocolVersion ?: 0,
                canRelay = existing?.canRelay ?: false,
                canStoreForward = existing?.canStoreForward ?: false,
                isSavedForChat = existing?.isSavedForChat ?: false
            )
        )
    }

    suspend fun addIncoming(record: MessageRecordEntity): Boolean {
        require(!record.senderNodeId.equals(identityStore.getIdentity().nodeId, ignoreCase = true))
        val inserted = messages.insertIncoming(record) != -1L
        if (inserted) ensurePeer(record.senderNodeId)
        return inserted
    }

    suspend fun addOutgoing(
        content: String,
        recipientNodeId: String,
        messageType: String
    ): MessageRecordEntity {
        val cleanRecipient = normalizeNodeId(recipientNodeId)
        require(content.isNotBlank())
        require(content.toByteArray(Charsets.UTF_8).size <= 12 * 1024) {
            "content exceeds mesh payload limit"
        }
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
            lastError = null,
            deliveryHopCount = null
        )

        messages.insert(record)
        recordTraceEvent(messageId = record.messageId, eventType = "MESSAGE_CREATED", resultCode = "QUEUED")
        ensurePeer(cleanRecipient)
        return record
    }

    suspend fun addOutgoingAttachment(
        recipientNodeId: String,
        mimeType: String,
        displayName: String,
        sizeBytes: Long,
        sha256: String,
        localPath: String
    ): Pair<MessageRecordEntity, AttachmentRecordEntity> {
        val cleanRecipient = normalizeNodeId(recipientNodeId)
        require(NodeIdGenerator.isValid(cleanRecipient))
        require(!cleanRecipient.equals(identityStore.getIdentity().nodeId, ignoreCase = true))
        require(sizeBytes in 1..AttachmentDescriptor.MAX_ATTACHMENT_BYTES)
        require(mimeType.startsWith("image/") || mimeType.startsWith("video/"))
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))

        val localNodeId = identityStore.getIdentity().nodeId
        val now = System.currentTimeMillis()
        val messageId = "HMRM-${localNodeId.removePrefix("HMR-").lowercase()}::${UUID.randomUUID()}"
        val descriptor = AttachmentDescriptor(
            messageId = messageId,
            mimeType = mimeType.lowercase(),
            displayName = displayName.take(180),
            sizeBytes = sizeBytes,
            sha256 = sha256.lowercase()
        )
        val message = MessageRecordEntity(
            messageId = messageId,
            senderNodeId = localNodeId,
            recipientNodeId = cleanRecipient,
            content = descriptor.encode(),
            createdAt = now,
            messageType = MessageType.ATTACHMENT.name,
            status = DeliveryStatus.QUEUED.name,
            lastTransport = null,
            deliveredAt = null,
            attemptCount = 0,
            nextAttemptAt = now,
            lastError = null,
            deliveryHopCount = null
        )
        val attachment = AttachmentRecordEntity(
            messageId = messageId,
            senderNodeId = localNodeId,
            recipientNodeId = cleanRecipient,
            mimeType = descriptor.mimeType,
            displayName = descriptor.displayName,
            sizeBytes = sizeBytes,
            sha256 = descriptor.sha256,
            localPath = localPath,
            status = AttachmentTransferStatus.QUEUED,
            receivedFromNodeId = null,
            hopCount = 0,
            createdAt = now,
            transferredBytes = 0L,
            updatedAt = now
        )
        database.withTransaction {
            messages.insert(message)
            attachments.upsert(attachment)
        }
        recordTraceEvent(messageId, "MESSAGE_CREATED", resultCode = "ATTACHMENT_QUEUED")
        ensurePeer(cleanRecipient)
        return message to attachment
    }

    suspend fun prepareIncomingAttachment(
        manifest: AttachmentTransferManifest,
        localPath: String,
        resumeOffset: Long,
        receivedFromNodeId: String,
        localCanRelay: Boolean
    ): Boolean {
        val localNodeId = identityStore.getIdentity().nodeId
        val isFinalRecipient = manifest.recipientNodeId.equals(localNodeId, true)
        if (!manifest.isValid() || receivedFromNodeId.isBlank() ||
            (!isFinalRecipient && (!localCanRelay || manifest.hopCount >= AttachmentTransferManifest.MAX_HOPS))) return false
        val now = System.currentTimeMillis()
        val attachment = AttachmentRecordEntity(
            messageId = manifest.messageId,
            senderNodeId = manifest.senderNodeId,
            recipientNodeId = manifest.recipientNodeId,
            mimeType = manifest.mimeType,
            displayName = manifest.displayName,
            sizeBytes = manifest.sizeBytes,
            sha256 = manifest.sha256.lowercase(),
            // Do not expose a partial .part receive as an outgoing relay file.
            // The completed path only becomes routable after its hash is verified.
            localPath = java.io.File(localPath).takeIf { it.isFile && it.length() == manifest.sizeBytes }?.absolutePath,
            status = AttachmentTransferStatus.RECEIVING,
            receivedFromNodeId = receivedFromNodeId,
            hopCount = manifest.hopCount,
            createdAt = manifest.createdAt,
            transferredBytes = resumeOffset.coerceIn(0L, manifest.sizeBytes),
            updatedAt = now
        )
        val persisted = database.withTransaction {
            val existing = attachments.get(manifest.messageId)
            if (existing != null && (
                    !existing.senderNodeId.equals(manifest.senderNodeId, true) ||
                        !existing.recipientNodeId.equals(manifest.recipientNodeId, true) ||
                        existing.sizeBytes != manifest.sizeBytes ||
                        !existing.sha256.equals(manifest.sha256, true) ||
                        !existing.mimeType.equals(manifest.mimeType, true)
                    )) return@withTransaction false
            if (existing?.status == AttachmentTransferStatus.DELIVERED && existing.localPath != null) {
                return@withTransaction true
            }
            attachments.upsert(attachment)
            true
        }
        if (persisted && isFinalRecipient) ensurePeer(manifest.senderNodeId)
        return persisted
    }

    suspend fun completeIncomingAttachment(
        manifest: AttachmentTransferManifest,
        localPath: String,
        receivedFromNodeId: String,
        localCanRelay: Boolean
    ): Boolean =
        database.withTransaction {
            val localNodeId = identityStore.getIdentity().nodeId
            val isFinalRecipient = manifest.recipientNodeId.equals(localNodeId, true)
            if (!manifest.isValid() || receivedFromNodeId.isBlank() ||
                (!isFinalRecipient && (!localCanRelay || manifest.hopCount >= AttachmentTransferManifest.MAX_HOPS))) return@withTransaction false
            val now = System.currentTimeMillis()
            val existing = attachments.get(manifest.messageId)
            if (existing != null && (
                    !existing.senderNodeId.equals(manifest.senderNodeId, true) ||
                        !existing.recipientNodeId.equals(manifest.recipientNodeId, true) ||
                        existing.sizeBytes != manifest.sizeBytes ||
                        !existing.sha256.equals(manifest.sha256, true) ||
                        !existing.mimeType.equals(manifest.mimeType, true)
                    )) return@withTransaction false
            if (isFinalRecipient && messages.getById(manifest.messageId) == null) {
                val descriptor = AttachmentDescriptor(
                    messageId = manifest.messageId,
                    mimeType = manifest.mimeType,
                    displayName = manifest.displayName,
                    sizeBytes = manifest.sizeBytes,
                    sha256 = manifest.sha256.lowercase()
                )
                messages.insertIncoming(
                    MessageRecordEntity(
                        messageId = manifest.messageId,
                        senderNodeId = manifest.senderNodeId,
                        recipientNodeId = manifest.recipientNodeId,
                        content = descriptor.encode(),
                        createdAt = manifest.createdAt,
                        messageType = MessageType.ATTACHMENT.name,
                        status = DeliveryStatus.DELIVERED.name,
                        lastTransport = "WIFI_DIRECT",
                        deliveredAt = now,
                        attemptCount = 0,
                        nextAttemptAt = null,
                        lastError = null,
                        deliveryHopCount = manifest.hopCount
                    )
                )
            }
            attachments.upsert(
                AttachmentRecordEntity(
                    messageId = manifest.messageId,
                    senderNodeId = manifest.senderNodeId,
                    recipientNodeId = manifest.recipientNodeId,
                    mimeType = manifest.mimeType,
                    displayName = manifest.displayName,
                    sizeBytes = manifest.sizeBytes,
                    sha256 = manifest.sha256.lowercase(),
                    localPath = localPath,
                    status = if (isFinalRecipient) AttachmentTransferStatus.DELIVERED else AttachmentTransferStatus.RELAY_QUEUED,
                    receivedFromNodeId = receivedFromNodeId,
                    hopCount = manifest.hopCount,
                    createdAt = manifest.createdAt,
                    transferredBytes = manifest.sizeBytes,
                    updatedAt = now
                )
            )
            (existing == null || existing.messageId == manifest.messageId) &&
                (!isFinalRecipient || messages.getById(manifest.messageId) != null)
        }

    suspend fun markAttachmentForwarded(record: AttachmentRecordEntity, finalRecipientHop: Boolean) {
        val isOrigin = record.senderNodeId.equals(identityStore.getIdentity().nodeId, true)
        if (!isOrigin) {
            record.localPath?.let { runCatching { java.io.File(it).delete() } }
            attachments.updateStatusAndPath(
                record.messageId,
                AttachmentTransferStatus.RELAYED,
                null,
                System.currentTimeMillis(),
                null
            )
        } else {
            attachments.updateProgress(
                record.messageId,
                if (finalRecipientHop) AttachmentTransferStatus.DELIVERED else AttachmentTransferStatus.RELAYED,
                record.sizeBytes,
                System.currentTimeMillis(),
                null
            )
        }
    }

    suspend fun expireAttachment(record: AttachmentRecordEntity) {
        val isOrigin = record.senderNodeId.equals(identityStore.getIdentity().nodeId, true)
        if (!isOrigin) record.localPath?.let { runCatching { java.io.File(it).delete() } }
        attachments.updateStatusAndPath(
            record.messageId,
            AttachmentTransferStatus.FAILED,
            record.localPath.takeIf { isOrigin },
            System.currentTimeMillis(),
            "ATTACHMENT_EXPIRED"
        )
    }

    suspend fun claimForDelivery(messageId: String, deliveryDeadline: Long): Boolean =
        messages.claimForDelivery(messageId, deliveryDeadline) == 1

    suspend fun updateLastTransport(messageId: String, transport: String) {
        messages.updateLastTransport(messageId, transport)
    }

    suspend fun markDelivered(
        messageId: String,
        transport: String?,
        deliveredAt: Long = System.currentTimeMillis(),
        deliveryHopCount: Int = 0
    ) {
        messages.markDelivered(
            messageId = messageId,
            localNodeId = identityStore.getIdentity().nodeId,
            transport = transport,
            deliveredAt = deliveredAt,
            deliveryHopCount = deliveryHopCount
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
            lastError = error,
            deliveryHopCount = null
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

    suspend fun requeueIfInFlight(messageId: String, nextAttemptAt: Long, error: String) {
        messages.requeueIfInFlight(messageId, nextAttemptAt, error)
    }

    suspend fun deferQueuedMessage(
        messageId: String,
        nextAttemptAt: Long,
        error: String
    ) {
        messages.deferQueuedMessage(
            messageId = messageId,
            nextAttemptAt = nextAttemptAt,
            lastError = error
        )
    }

    suspend fun makeRecipientEligible(peerNodeId: String) {
        messages.makeRecipientEligible(
            localNodeId = identityStore.getIdentity().nodeId,
            peerNodeId = normalizeNodeId(peerNodeId),
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

    suspend fun getDueInFlight(now: Long = System.currentTimeMillis(), limit: Int = 8): List<MessageRecordEntity> =
        messages.getDueInFlight(identityStore.getIdentity().nodeId, now, limit)

    suspend fun getEarliestNextAttemptAt(): Long? =
        messages.getEarliestNextAttemptAt(identityStore.getIdentity().nodeId)

    suspend fun getById(messageId: String): MessageRecordEntity? = messages.getById(messageId)

    data class DestinationAcceptance(
        val firstPacket: Boolean,
        val insertedMessage: Boolean,
        val messagePersisted: Boolean,
        val deliveryHopCount: Int
    )

    suspend fun acceptDestinationPacket(
        seen: MeshSeenPacketEntity,
        record: MessageRecordEntity,
        attachment: AttachmentRecordEntity? = null
    ): DestinationAcceptance = database.withTransaction {
        val firstPacket = seenPackets.insert(seen) != -1L
        val existing = messages.getById(record.messageId)

        if (existing != null) {
            attachment?.let { attachments.insert(it) }
            // A durable application record is the source of truth. A seen-cache
            // entry by itself must never be treated as proof of delivery.
            DestinationAcceptance(
                firstPacket = firstPacket,
                insertedMessage = false,
                messagePersisted = true,
                deliveryHopCount = existing.deliveryHopCount ?: record.deliveryHopCount ?: 0
            )
        } else {
            // Repair an inconsistent database where the seen-packet cache survived
            // but the application record did not. INSERT IGNORE plus a read-back
            // makes the ACK decision depend on durable state, including races.
            val inserted = messages.insertIncoming(record) != -1L
            val persisted = messages.getById(record.messageId) != null
            if (persisted) attachment?.let { attachments.insert(it) }
            DestinationAcceptance(
                firstPacket = firstPacket,
                insertedMessage = inserted && persisted,
                messagePersisted = persisted,
                deliveryHopCount = record.deliveryHopCount ?: 0
            )
        }
    }

    suspend fun acceptRelayPacket(
        seen: MeshSeenPacketEntity,
        forwardingRecord: MeshForwardingRecordEntity
    ): Boolean = database.withTransaction {
        val insertedSeen = seenPackets.insert(seen) != -1L
        if (!insertedSeen) {
            // A clean receive path writes both rows atomically. If an older or
            // partially-recovered database contains only the cache entry,
            // reconstruct the missing forwarding work instead of black-holing
            // the packet because it is already marked seen.
            if (forwarding.get(forwardingRecord.packetId) == null) {
                return@withTransaction forwarding.insert(forwardingRecord) != -1L
            }
            return@withTransaction true
        }
        forwarding.insert(forwardingRecord) != -1L
    }

    suspend fun acceptDeliveryAck(
        seen: MeshSeenPacketEntity,
        messageId: String,
        deliveredAt: Long,
        deliveryHopCount: Int,
        routeTrace: String? = null,
        routeTraceComplete: Boolean = false
    ): Boolean = database.withTransaction {
        val existing = messages.getById(messageId) ?: return@withTransaction false
        seenPackets.insert(seen)

        val updated = messages.markDelivered(
            messageId = messageId,
            localNodeId = identityStore.getIdentity().nodeId,
            // Delivery ACK can return over a different bearer; preserve the
            // last physical transport used for the original message's local hop.
            transport = null,
            deliveredAt = deliveredAt,
            deliveryHopCount = deliveryHopCount
        ) > 0

        if (!routeTrace.isNullOrBlank()) messages.updateRouteTrace(messageId, routeTrace.take(32), routeTraceComplete)

        when {
            updated -> true
            existing.status == DeliveryStatus.DELIVERED.name -> true
            else -> false
        }
    }

    suspend fun insertSeenPacket(record: MeshSeenPacketEntity): Boolean =
        seenPackets.insert(record) != -1L

    suspend fun isPacketSeen(packetId: String): Boolean =
        seenPackets.get(packetId) != null

    suspend fun getSeenPacket(packetId: String): MeshSeenPacketEntity? =
        seenPackets.get(packetId)

    suspend fun improveForwarding(
        packetId: String,
        ttl: Int,
        hopCount: Int,
        receivedFromNodeId: String?,
        receivedFromAddress: String?,
        routeTrace: String,
        routeTraceComplete: Boolean,
        nextAttemptAt: Long
    ): Boolean =
        forwarding.improveRecord(
            packetId = packetId,
            ttl = ttl,
            hopCount = hopCount,
            receivedFromNodeId = receivedFromNodeId,
            receivedFromAddress = receivedFromAddress,
            routeTrace = routeTrace.take(32),
            routeTraceComplete = routeTraceComplete,
            nextAttemptAt = nextAttemptAt
        ) > 0

    suspend fun updateSeenObservation(
        packetId: String,
        hopCount: Int,
        expiresAt: Long
    ) = seenPackets.updateObservation(packetId, hopCount, expiresAt)

    suspend fun insertForwardingRecord(record: MeshForwardingRecordEntity): Boolean =
        forwarding.insert(record) != -1L

    suspend fun getPendingForwards(now: Long, limit: Int): List<MeshForwardingRecordEntity> =
        forwarding.getPending(now, limit)

    suspend fun getEarliestForwardNextAttemptAt(now: Long = System.currentTimeMillis()): Long? =
        forwarding.getEarliestNextAttemptAt(now)

    suspend fun makePendingForwardingEligible(now: Long = System.currentTimeMillis()) {
        forwarding.makePendingEligible(now)
    }

    suspend fun getForwarding(packetId: String): MeshForwardingRecordEntity? =
        forwarding.get(packetId)

    suspend fun claimForward(packetId: String): Boolean = forwarding.claim(packetId) == 1

    suspend fun markForwarded(packetId: String): Int = forwarding.markForwarded(packetId)

    suspend fun requeueAfterBetterCopy(packetId: String, now: Long = System.currentTimeMillis()): Int =
        forwarding.requeueAfterBetterCopy(packetId, now)

    suspend fun requeueForwarded(packetId: String, now: Long = System.currentTimeMillis(), error: String = "DELIVERY_ACK_RETRY") =
        forwarding.requeueForwarded(packetId, now, error)

    suspend fun markForwardInvalid(packetId: String, error: String = "INVALID_FORWARDING_STATE") =
        forwarding.markInvalid(packetId, error)

    suspend fun scheduleForwardRetry(packetId: String, nextAttemptAt: Long, error: String) =
        forwarding.scheduleRetry(packetId, nextAttemptAt, error)

    suspend fun resetStaleForwarding() {
        val now = System.currentTimeMillis()
        forwarding.resetStaleForwarding(now, "SERVICE_RESTARTED")
        forwarding.purgeExpired(now)
    }

    suspend fun requeueForwardingOnTransportLoss() {
        val now = System.currentTimeMillis()
        forwarding.requeueOnTransportLoss(now, "TRANSPORT_UNAVAILABLE")
    }

    suspend fun markForwardExpired(packetId: String) = forwarding.markExpired(packetId)

    suspend fun pendingForwardCount(now: Long = System.currentTimeMillis()): Int =
        forwarding.countPending(now)

    suspend fun activeSeenPacketCount(now: Long = System.currentTimeMillis()): Int =
        seenPackets.countActive(now)

    suspend fun purgeMeshState(now: Long = System.currentTimeMillis()) {
        seenPackets.purgeExpired(now)
        forwarding.purgeExpired(now)
        traceEvents.deleteOlderThan(now - TRACE_RETENTION_MS)
        traceEvents.deleteExcess(MAX_TRACE_EVENTS)
    }

    suspend fun deleteMessage(messageId: String) {
        traceEvents.deleteForMessage(messageId)
        attachments.get(messageId)?.localPath?.let { path -> runCatching { java.io.File(path).delete() } }
        AttachmentFileStore.deleteReceivedFiles(appContext, messageId)
        attachments.delete(messageId)
        messages.delete(messageId)
    }

    suspend fun deleteConversation(peerNodeId: String) {
        val localId = identityStore.getIdentity().nodeId
        val normalizedPeer = normalizeNodeId(peerNodeId)
        traceEvents.deleteForConversation(localId, normalizedPeer)
        // Collect once so files are removed before their durable metadata rows.
        val attachmentRows = attachments.getForConversation(localId, normalizedPeer)
        attachmentRows.mapNotNull { it.localPath }.forEach { path -> runCatching { java.io.File(path).delete() } }
        attachmentRows.forEach { AttachmentFileStore.deleteReceivedFiles(appContext, it.messageId) }
        attachments.deleteConversation(localId, normalizedPeer)
        messages.deleteConversation(localId, normalizedPeer)
        peers.removeFromChats(normalizedPeer)
    }

    companion object {
        private const val TRACE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_TRACE_EVENTS = 5_000

        @Volatile
        private var INSTANCE: MessagingRepository? = null

        fun getInstance(context: Context): MessagingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessagingRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
