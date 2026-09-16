package com.hybridmesh.relay.messaging.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.isActive
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MeshSeenPacketEntity
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.notifications.MessagingNotificationCoordinator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Complete Neyra mesh runtime.
 *
 * The engine is a managed-flooding/store-and-forward layer above the existing
 * GATT transport. GATT acknowledgements only prove one-hop transport acceptance;
 * a final DELIVERY_ACK from the destination proves application delivery.
 */
class MeshEngine(context: Context) {
    private val appContext = context.applicationContext
    private val networkManager = NetworkManager.getInstance(appContext)
    private val repository = MessagingRepository.getInstance(appContext)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val gattClient = BleGattClient(appContext)
    private val notificationCoordinator =
        MessagingNotificationCoordinator.getInstance(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val peerLocks = ConcurrentHashMap<String, Mutex>()

    private val _snapshot = MutableStateFlow(MeshRuntimeSnapshot())
    val snapshot: StateFlow<MeshRuntimeSnapshot> = _snapshot.asStateFlow()

    private val _transport = MutableStateFlow(GattTransportSnapshot())
    val transport: StateFlow<GattTransportSnapshot> = _transport.asStateFlow()

    @Volatile
    private var started = false

    private val jobs = mutableListOf<Job>()

    @Synchronized
    fun start() {
        if (started) return
        started = true

        _snapshot.value = _snapshot.value.copy(
            running = true,
            updatedAt = System.currentTimeMillis()
        )

        jobs += scope.launch {
            repository.observeQueuedOutgoing()
                .distinctUntilChanged()
                .collect { wakeChannel.trySend(Unit) }
        }

        jobs += scope.launch {
            networkManager.state
                .map { state ->
                    state.peers.map { peer ->
                        "${peer.nodeId}|${peer.address}|${peer.lastSeen}|${peer.meshProtocolVersion}|${peer.canRelay}"
                    }
                }
                .distinctUntilChanged()
                .collect { wakeChannel.trySend(Unit) }
        }

        jobs += scope.launch {
            networkManager.runtimeEvents.collect {
                wakeChannel.trySend(Unit)
            }
        }

        jobs += scope.launch { originLoop() }
        jobs += scope.launch { forwardingLoop() }
        jobs += scope.launch { maintenanceLoop() }
        jobs += scope.launch { snapshotLoop() }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        jobs.forEach(Job::cancel)
        jobs.clear()
        _snapshot.value = _snapshot.value.copy(
            running = false,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun onTransportPacket(device: BluetoothDevice, payload: ByteArray): Boolean {
        if (!started) return false
        val address = runCatching { device.address }.getOrDefault("Unknown")
        return handleReceivedPacket(payload, address)
    }

    private suspend fun originLoop() {
        while (currentCoroutineContext().isActive && started) {
            val now = System.currentTimeMillis()
            val state = networkManager.state.value
            val ready = state.bleSupported &&
                state.permissionsGranted &&
                state.bluetoothState == BluetoothState.ON &&
                state.gattServerReady

            if (!ready) {
                waitForWake(5_000L)
                continue
            }

            var worked = false

            for (message in repository.getEligibleOutgoing(now).take(MAX_ORIGIN_BATCH)) {
                if (!message.senderNodeId.equals(identityStore.getIdentity().nodeId, true)) {
                    continue
                }

                if (isExpired(message.createdAt, now)) {
                    repository.markFailed(
                        message.messageId,
                        MESH_TRANSPORT_NAME,
                        "DELIVERY_EXPIRED"
                    )
                    worked = true
                    continue
                }

                val peers = MeshForwardingPolicy.select(
                    peers = state.peers,
                    localNodeId = identityStore.getIdentity().nodeId,
                    destinationNodeId = message.recipientNodeId,
                    excludeNodeId = null,
                    excludeAddress = null,
                    packetId = message.messageId
                )

                if (peers.isEmpty()) {
                    repository.deferQueuedMessage(
                        message.messageId,
                        now + NO_PEER_RETRY_MS,
                        "NO_MESH_NEIGHBOR"
                    )
                    worked = true
                    continue
                }

                val deadline = now + ORIGIN_ACK_TIMEOUT_MS
                if (!repository.claimForDelivery(message.messageId, deadline)) {
                    continue
                }

                val packet = MeshPacket(
                    packetId = MeshPacketCodec.stableDataPacketId(message.messageId),
                    messageId = message.messageId,
                    originNodeId = message.senderNodeId,
                    destinationNodeId = message.recipientNodeId,
                    createdAt = message.createdAt,
                    expiresAt = minOf(
                        message.createdAt + MESSAGE_LIFETIME_MS,
                        now + MESSAGE_LIFETIME_MS
                    ),
                    ttl = INITIAL_TTL,
                    hopCount = 0,
                    packetType = MeshPacket.PacketType.DATA,
                    messageType = message.messageType,
                    content = message.content
                )

                val accepted = sendPacketToPeers(packet, peers)

                if (!accepted) {
                    repository.requeueIfInFlight(
                        message.messageId,
                        now + originRetryDelay(message.attemptCount),
                        "MESH_TRANSPORT_FAILED"
                    )
                }

                worked = true
            }

            for (message in repository.getDueInFlight(now).take(MAX_ORIGIN_BATCH)) {
                if (isExpired(message.createdAt, now)) {
                    repository.markFailed(
                        message.messageId,
                        MESH_TRANSPORT_NAME,
                        "DELIVERY_EXPIRED"
                    )
                } else {
                    repository.requeueIfInFlight(
                        message.messageId,
                        now,
                        "MESH_DELIVERY_ACK_TIMEOUT"
                    )
                }
                worked = true
            }

            if (!worked) {
                waitForWake(originWaitTime(now))
            }
        }
    }

    private suspend fun forwardingLoop() {
        while (currentCoroutineContext().isActive && started) {
            val now = System.currentTimeMillis()
            val candidates = repository.getPendingForwards(now, MAX_FORWARD_BATCH)
            var worked = false

            for (record in candidates) {
                if (record.expiresAt <= now) {
                    repository.markForwardExpired(record.packetId)
                    worked = true
                    continue
                }

                if (!repository.claimForward(record.packetId)) {
                    continue
                }

                val packet = record.toPacket()
                if (packet == null) {
                    repository.markForwardInvalid(record.packetId)
                    worked = true
                    continue
                }
                if (packet.ttl <= 0) {
                    repository.markForwardExpired(record.packetId)
                    worked = true
                    continue
                }

                val peers = MeshForwardingPolicy.select(
                    peers = networkManager.state.value.peers,
                    localNodeId = identityStore.getIdentity().nodeId,
                    destinationNodeId = packet.destinationNodeId,
                    excludeNodeId = record.receivedFromNodeId,
                    excludeAddress = record.receivedFromAddress,
                    packetId = record.packetId
                )

                if (peers.isEmpty()) {
                    repository.scheduleForwardRetry(
                        record.packetId,
                        now + FORWARD_NO_PEER_RETRY_MS,
                        "NO_MESH_NEIGHBOR"
                    )
                    worked = true
                    continue
                }

                delay((150L..500L).random())

                val forwardedPacket = packet.copy(
                    ttl = packet.ttl - 1,
                    hopCount = packet.hopCount + 1
                )

                val accepted = sendPacketToPeers(
                    forwardedPacket,
                    peers
                )

                if (accepted) {
                    repository.markForwarded(record.packetId)
                } else {
                    repository.scheduleForwardRetry(
                        record.packetId,
                        now + forwardRetryDelay(record.attemptCount),
                        "MESH_TRANSPORT_FAILED"
                    )
                }

                worked = true
            }

            if (!worked) {
                waitForWake(FORWARD_IDLE_WAIT_MS)
            }
        }
    }

    private suspend fun handleReceivedPacket(
        payload: ByteArray,
        sourceAddress: String
    ): Boolean {
        val packet = MeshPacketCodec.decode(payload) ?: return false
        val now = System.currentTimeMillis()

        if (packet.expiresAt <= now) return false
        if (packet.createdAt > now + MAX_CLOCK_SKEW_MS) return false
        if (packet.ttl !in 0..MAX_TTL) return false
        if (packet.hopCount !in 0..MAX_TTL) return false

        val localNodeId = identityStore.getIdentity().nodeId
        if (packet.originNodeId.equals(localNodeId, true)) {
            return false
        }

        return when (packet.packetType) {
            MeshPacket.PacketType.DATA -> {
                if (packet.messageType !in APPLICATION_MESSAGE_TYPES) return false
                if (packet.destinationNodeId.equals(localNodeId, true)) {
                    handleDataForLocalDestination(packet, now)
                } else if (packet.ttl > 0 && packet.hopCount < MAX_TTL) {
                    acceptForRelay(packet, sourceAddress, now)
                } else {
                    false
                }
            }

            MeshPacket.PacketType.DELIVERY_ACK -> {
                if (packet.messageType != DELIVERY_ACK_TYPE) return false
                if (packet.deliveryHopCount !in 0..MAX_TTL) return false

                if (packet.destinationNodeId.equals(localNodeId, true)) {
                    handleDeliveryAckForOrigin(packet, now)
                } else if (packet.ttl > 0 && packet.hopCount < MAX_TTL) {
                    acceptForRelay(packet, sourceAddress, now)
                } else {
                    false
                }
            }
        }
    }

    private suspend fun acceptForRelay(
        packet: MeshPacket,
        sourceAddress: String,
        now: Long
    ): Boolean {
        val predecessorNodeId = networkManager.state.value.peers
            .firstOrNull { it.address.equals(sourceAddress, true) }
            ?.nodeId

        val existing = repository.getSeenPacket(packet.packetId)
        if (existing != null) {
            val betterPath = packet.hopCount < existing.bestHopCount
            repository.updateSeenObservation(
                packetId = packet.packetId,
                hopCount = packet.hopCount,
                expiresAt = packet.expiresAt + CACHE_GRACE_MS
            )

            if (betterPath) {
                repository.improveForwarding(
                    packetId = packet.packetId,
                    ttl = packet.ttl,
                    hopCount = packet.hopCount,
                    receivedFromNodeId = predecessorNodeId,
                    receivedFromAddress = sourceAddress,
                    nextAttemptAt = now + FORWARD_SUPPRESSION_RECHECK_MS
                )
                wakeChannel.trySend(Unit)
            }

            // Receiving the same packet more than once is still an accepted
            // transport event. More importantly, repair an inconsistent state
            // where the cache survived but the durable forwarding row did not.
            if (repository.getForwarding(packet.packetId) != null) return true

            val rebuilt = repository.insertForwardingRecord(
                packet.toForwardingRecord(predecessorNodeId, sourceAddress)
            )
            if (rebuilt) wakeChannel.trySend(Unit)
            return rebuilt
        }

        val accepted = repository.acceptRelayPacket(
            seen = packet.toSeenPacket(now),
            forwardingRecord = packet.toForwardingRecord(
                predecessor = predecessorNodeId,
                predecessorAddress = sourceAddress
            )
        )
        if (accepted) wakeChannel.trySend(Unit)
        return accepted
    }

    private suspend fun handleDataForLocalDestination(
        packet: MeshPacket,
        now: Long
    ): Boolean {
        val record = MessageRecordEntity(
            messageId = packet.messageId,
            senderNodeId = packet.originNodeId,
            recipientNodeId = packet.destinationNodeId,
            content = packet.content,
            createdAt = packet.createdAt,
            messageType = packet.messageType,
            status = DeliveryStatus.DELIVERED.name,
            lastTransport = MESH_TRANSPORT_NAME,
            deliveredAt = now,
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            deliveryHopCount = packet.hopCount
        )

        val acceptance = repository.acceptDestinationPacket(
            seen = packet.toSeenPacket(now),
            record = record
        )

        if (acceptance.insertedMessage) {
            val peer = repository.getPeer(packet.originNodeId)
            val body = if (packet.messageType == MessageType.LOCATION.name) {
                "Shared a location"
            } else {
                packet.content
            }
            notificationCoordinator.notifyIncoming(
                peerNodeId = packet.originNodeId,
                displayName = peer?.displayName
                    ?.takeIf { it.isNotBlank() && !it.equals(packet.originNodeId, true) }
                    ?: packet.originNodeId,
                body = body
            )
        }

        // One stable ACK packet per logical message prevents every flooded DATA
        // duplicate from creating a new ACK identity. If the prior ACK was already
        // forwarded, a fresh DATA copy can requeue that same ACK when necessary.
        val ackPacketId = MeshPacketCodec.stableDeliveryAckPacketId(packet.messageId)
        val existingAck = repository.getForwarding(ackPacketId)
        if (existingAck == null) {
            val ack = MeshPacket(
                packetId = ackPacketId,
                messageId = packet.messageId,
                originNodeId = identityStore.getIdentity().nodeId,
                destinationNodeId = packet.originNodeId,
                createdAt = now,
                expiresAt = now + ACK_LIFETIME_MS,
                ttl = ACK_INITIAL_TTL,
                hopCount = 0,
                packetType = MeshPacket.PacketType.DELIVERY_ACK,
                messageType = DELIVERY_ACK_TYPE,
                content = "",
                deliveryHopCount = acceptance.deliveryHopCount
            )
            val ackPersisted = repository.acceptRelayPacket(
                seen = ack.toSeenPacket(now),
                forwardingRecord = ack.toForwardingRecord(null, null)
            )
            if (!ackPersisted) return false
        } else if (existingAck.state == "FORWARDED") {
            repository.requeueForwarded(ackPacketId, now, "DELIVERY_ACK_RETRY")
        }

        wakeChannel.trySend(Unit)
        return true
    }

    private suspend fun handleDeliveryAckForOrigin(
        packet: MeshPacket,
        now: Long
    ): Boolean {
        val original = repository.getById(packet.messageId) ?: return false

        // A valid delivery ACK must be generated by the same node that the
        // original application message targeted, and must be addressed back
        // to this node.
        if (!original.senderNodeId.equals(identityStore.getIdentity().nodeId, true)) return false
        if (!original.recipientNodeId.equals(packet.originNodeId, true)) return false
        if (!packet.destinationNodeId.equals(identityStore.getIdentity().nodeId, true)) return false

        val delivered = repository.acceptDeliveryAck(
            seen = packet.toSeenPacket(now),
            messageId = packet.messageId,
            deliveredAt = now,
            deliveryHopCount = packet.deliveryHopCount ?: 0
        )

        if (delivered) {
            _snapshot.value = _snapshot.value.copy(
                lastDeliveredHopCount = packet.deliveryHopCount ?: 0,
                updatedAt = now
            )
        }
        return delivered
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacketToPeers(
        packet: MeshPacket,
        peers: List<BlePeer>
    ): Boolean {
        val bytes = runCatching {
            MeshPacketCodec.encode(packet)
        }.getOrNull() ?: return false

        var accepted = false

        for (peer in peers) {
            if (!started) break

            val address = peer.address
            if (address == "Unknown") continue

            val device = runCatching {
                bluetoothManager?.adapter?.getRemoteDevice(address)
            }.getOrNull() ?: continue

            val lock = peerLocks.computeIfAbsent(peer.nodeId) { Mutex() }
            if (!lock.tryLock()) continue

            try {
                val generation = networkManager.runtimeGeneration.value
                val state = networkManager.state.value
                if (state.bluetoothState != BluetoothState.ON || !state.gattServerReady) {
                    continue
                }

                val result = gattClient.sendPayload(
                    device = device,
                    payload = bytes,
                    messageId = packet.messageId,
                    transferId = MeshPacketCodec.newTransferId(),
                    runtimeGeneration = generation,
                    isRuntimeGenerationCurrent = {
                        networkManager.runtimeGeneration.value == generation &&
                            networkManager.state.value.bluetoothState == BluetoothState.ON &&
                            networkManager.state.value.gattServerReady
                    },
                    onState = { _transport.value = it }
                )

                if (result is BleGattClient.GattSendResult.Delivered) {
                    accepted = true
                }
            } finally {
                lock.unlock()
            }
        }

        return accepted
    }

    private suspend fun maintenanceLoop() {
        while (currentCoroutineContext().isActive && started) {
            repository.purgeMeshState()
            delay(MAINTENANCE_INTERVAL_MS)
        }
    }

    private suspend fun snapshotLoop() {
        while (currentCoroutineContext().isActive && started) {
            val now = System.currentTimeMillis()
            _snapshot.value = _snapshot.value.copy(
                running = true,
                relayEnabled = true,
                pendingForwarding = repository.pendingForwardCount(now),
                cachedPackets = repository.activeSeenPacketCount(now),
                updatedAt = now
            )
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    private suspend fun originWaitTime(now: Long): Long {
        val next = repository.getEarliestNextAttemptAt()
        return if (next == null) {
            5_000L
        } else {
            (next - now).coerceIn(250L, 5_000L)
        }
    }

    private suspend fun waitForWake(timeoutMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            wakeChannel.receive()
        }
    }

    private fun originRetryDelay(attemptCount: Int): Long {
        val shift = (attemptCount.coerceAtLeast(1) - 1).coerceAtMost(5)
        return (2_000L shl shift).coerceAtMost(60_000L)
    }

    private fun forwardRetryDelay(attemptCount: Int): Long {
        val shift = (attemptCount.coerceAtLeast(1) - 1).coerceAtMost(4)
        val exponential = (2_000L shl shift).coerceAtMost(30_000L)
        return exponential + (0L..750L).random()
    }

    private fun MeshPacket.toSeenPacket(now: Long): MeshSeenPacketEntity =
        MeshSeenPacketEntity(
            packetId = packetId,
            messageId = messageId,
            firstSeenAt = now,
            expiresAt = expiresAt + CACHE_GRACE_MS,
            bestHopCount = hopCount
        )

    private fun MeshPacket.toForwardingRecord(
        predecessor: String?,
        predecessorAddress: String?
    ): MeshForwardingRecordEntity =
        MeshForwardingRecordEntity(
            packetId = packetId,
            messageId = messageId,
            originNodeId = originNodeId,
            destinationNodeId = destinationNodeId,
            receivedFromNodeId = predecessor,
            receivedFromAddress = predecessorAddress,
            createdAt = createdAt,
            expiresAt = expiresAt,
            ttl = ttl,
            hopCount = hopCount,
            packetType = packetType.code.toInt(),
            messageType = messageType,
            content = content,
            deliveryHopCount = deliveryHopCount,
            state = "PENDING",
            attemptCount = 0,
            nextAttemptAt = System.currentTimeMillis() +
                (150L..500L).random(),
            lastError = null
        )

    private fun MeshForwardingRecordEntity.toPacket(): MeshPacket? {
        val type = MeshPacket.PacketType.entries.firstOrNull { it.code.toInt() == packetType }
            ?: return null
        val packet = runCatching {
            MeshPacket(
                packetId = packetId,
                messageId = messageId,
                originNodeId = originNodeId,
                destinationNodeId = destinationNodeId,
                createdAt = createdAt,
                expiresAt = expiresAt,
                ttl = ttl,
                hopCount = hopCount,
                packetType = type,
                messageType = messageType,
                content = content,
                deliveryHopCount = deliveryHopCount
            )
        }.getOrNull() ?: return null
        return runCatching { MeshPacketCodec.encode(packet); packet }.getOrNull()
    }

    private fun isExpired(createdAt: Long, now: Long): Boolean =
        now >= createdAt + MESSAGE_LIFETIME_MS

    companion object {
        const val INITIAL_TTL = MeshPacketCodec.INITIAL_TTL
        const val MAX_TTL = 32
        const val ACK_INITIAL_TTL = MeshPacketCodec.INITIAL_TTL
        const val MESSAGE_LIFETIME_MS = 30 * 60 * 1000L
        private const val ACK_LIFETIME_MS = 30 * 60 * 1000L
        private const val CACHE_GRACE_MS = 2 * 60 * 1000L
        private const val ORIGIN_ACK_TIMEOUT_MS = 30_000L
        private const val NO_PEER_RETRY_MS = 5_000L
        private const val FORWARD_NO_PEER_RETRY_MS = 10_000L
        private const val FORWARD_SUPPRESSION_RECHECK_MS = 250L
        private const val FORWARD_IDLE_WAIT_MS = 2_000L
        private const val MAINTENANCE_INTERVAL_MS = 30_000L
        private const val SNAPSHOT_INTERVAL_MS = 2_000L
        private const val MAX_ORIGIN_BATCH = 8
        private const val MAX_FORWARD_BATCH = 16
        private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
        const val MESH_TRANSPORT_NAME = "BLE_MESH"
        private const val DELIVERY_ACK_TYPE = "DELIVERY_ACK"

        private val APPLICATION_MESSAGE_TYPES = setOf(
            MessageType.NORMAL.name,
            MessageType.PRIORITY.name,
            MessageType.EMERGENCY.name,
            MessageType.LOCATION.name
        )

        @Volatile
        private var INSTANCE: MeshEngine? = null

        fun getInstance(context: Context): MeshEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshEngine(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }
}
