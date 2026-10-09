package com.hybridmesh.relay.messaging.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.isActive
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MeshSeenPacketEntity
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.notifications.MessagingNotificationCoordinator
import com.hybridmesh.relay.wifi.WifiDirectManager
import com.hybridmesh.relay.wifi.WifiPeerSessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Complete Neyra mesh runtime.
 *
 * The engine is a managed-flooding/store-and-forward layer above transport adapters.
 * One-hop acceptance is distinct from the final application DELIVERY_ACK.
 */
class MeshEngine(context: Context) {
    private val appContext = context.applicationContext
    private val networkManager = NetworkManager.getInstance(appContext)
    private val repository = MessagingRepository.getInstance(appContext)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val peerDirectory = MeshPeerDirectory()
    private val gattClient = BleGattClient(appContext)
    private val bleTransport = BleGattMeshTransport(
        context = appContext,
        networkManager = networkManager,
        gattClient = gattClient,
        onState = { _transport.value = it }
    )
    private val notificationCoordinator =
        MessagingNotificationCoordinator.getInstance(appContext)

    private val wifiDirectManager = WifiDirectManager(
        context = appContext,
        onPeerConnected = ::onWifiPeerConnected,
        onPacket = ::onWifiTransportPacket,
        onPeerDisconnected = ::removeWifiPeer
    )
    private val wifiTransport = WifiDirectMeshTransport(wifiDirectManager)
    private val meshTransports = listOf<MeshTransport>(bleTransport, wifiTransport)
    private val transportSelector = TransportSelector { meshTransports }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val receivedAtByPacket = ConcurrentHashMap<String, Long>()
    private val forwardingSlots = Semaphore(MAX_CONCURRENT_FORWARD_PACKETS)
    private val originSlots = Semaphore(MAX_CONCURRENT_ORIGIN_SENDS)
    private val transportSendSlots = Semaphore(MAX_CONCURRENT_TRANSPORT_SENDS)
    private val fanoutAttemptSlots = Semaphore(MAX_CONCURRENT_FANOUT_ATTEMPTS)
    private val activeFanoutJobs = ConcurrentHashMap.newKeySet<Job>()

    private val _snapshot = MutableStateFlow(MeshRuntimeSnapshot())
    val snapshot: StateFlow<MeshRuntimeSnapshot> = _snapshot.asStateFlow()
    /** Live endpoints discovered by BLE and Wi-Fi Direct, independent of either UI runtime. */
    val peers: StateFlow<List<MeshPeer>> = peerDirectory.snapshot

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
        // Optional transport: missing hardware/permission must not block BLE.
        wifiDirectManager.start()

        jobs += scope.launch {
            repository.observeQueuedOutgoing()
                .distinctUntilChanged()
                .collect { wakeChannel.trySend(Unit) }
        }

        jobs += scope.launch {
            var previousTopology = emptyList<String>()
            networkManager.state.collect { state ->
                peerDirectory.replaceBlePeers(state.peers)
                val topology = state.peers.map {
                    "${it.nodeId}|${it.address}|${it.meshProtocolVersion}|${it.canRelay}|${it.canStoreForward}"
                }.sorted()
                if (topology != previousTopology) {
                    previousTopology = topology
                    repository.makeAllQueuedEligible()
                    repository.makePendingForwardingEligible()
                }
                if (!wifiDirectManager.isStarted()) wifiDirectManager.start()
                wakeChannel.trySend(Unit)
            }
        }

        jobs += scope.launch {
            networkManager.runtimeEvents.collect {
                wakeChannel.trySend(Unit)
            }
        }

        // Permissions and nickname setup can happen after the service starts.
        // Retry optional Wi-Fi initialization slowly without blocking BLE or polling aggressively.
        jobs += scope.launch {
            while (currentCoroutineContext().isActive && started) {
                if (!wifiDirectManager.isStarted()) wifiDirectManager.start()
                delay(WIFI_START_RETRY_MS)
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
        activeFanoutJobs.toList().forEach(Job::cancel)
        activeFanoutJobs.clear()
        wifiDirectManager.stop()
        receivedAtByPacket.clear()
        _snapshot.value = _snapshot.value.copy(
            running = false,
            availableTransports = emptyList(),
            wifiDirectDiscoveryActive = false,
            connectedWifiPeers = 0,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** BLE-specific ingress adapter; core packet handling below is bearer-neutral. */
    suspend fun onBleTransportPacket(device: BluetoothDevice, payload: ByteArray): Boolean {
        val address = runCatching { device.address }.getOrDefault("Unknown")
        val sourceNodeId = peerDirectory.findByBleAddress(address)?.nodeId
        return onTransportPacket(
            sourceNodeId = sourceNodeId,
            sourceAddress = address,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = payload
        )
    }

    /** Shared ingress used by every bearer after its local peer context is resolved. */
    suspend fun onTransportPacket(
        sourceNodeId: String?,
        sourceAddress: String,
        sourceTransport: MeshTransportKind,
        payload: ByteArray
    ): Boolean {
        if (!started) return false
        return handleReceivedPacket(
            payload = payload,
            sourceNodeId = sourceNodeId,
            sourceAddress = sourceAddress,
            sourceTransport = sourceTransport
        )
    }

    /** Called by Wi-Fi after its node-identity handshake succeeds. */
    suspend fun onWifiTransportPacket(sourceNodeId: String, payload: ByteArray): Boolean =
        onTransportPacket(
            sourceNodeId = sourceNodeId.takeIf { it.isNotBlank() },
            sourceAddress = "wifi:$sourceNodeId",
            sourceTransport = MeshTransportKind.WIFI_DIRECT,
            payload = payload
        )

    private fun onWifiPeerConnected(peer: WifiPeerSessionInfo) {
        if (!started) return
        peerDirectory.observeWifi(
            nodeId = peer.nodeId,
            deviceName = peer.deviceName,
            host = peer.host,
            port = peer.port,
            protocolVersion = peer.protocolVersion,
            canRelay = peer.canRelay,
            canStoreForward = peer.canStoreForward
        )
        scope.launch {
            repository.updateWifiDiscoveredPeer(
                nodeId = peer.nodeId,
                displayName = peer.deviceName,
                protocolVersion = peer.protocolVersion,
                canRelay = peer.canRelay,
                canStoreForward = peer.canStoreForward
            )
        }
        wakeChannel.trySend(Unit)
    }

    fun removeWifiPeer(nodeId: String) {
        peerDirectory.removeWifi(nodeId)
        wakeChannel.trySend(Unit)
    }

    fun hasAlternativeTransportAvailable(failedTransport: MeshTransportKind): Boolean =
        meshTransports.any { it.kind != failedTransport && it.isAvailable() }

    private suspend fun originLoop() {
        while (currentCoroutineContext().isActive && started) {
            if (meshTransports.none { it.isAvailable() }) {
                waitForWake(5_000L)
                continue
            }

            val now = System.currentTimeMillis()
            val eligible = repository.getEligibleOutgoing(now).take(MAX_ORIGIN_BATCH)
            var worked = if (eligible.isEmpty()) {
                false
            } else {
                // Bound concurrent origins so a slow peer does not serialize all
                // other outgoing messages, without opening unbounded radio work.
                coroutineScope {
                    eligible.map { message ->
                        async { originSlots.withPermit { processOutgoingMessage(message) } }
                    }.awaitAll().any { it }
                }
            }

            for (message in repository.getDueInFlight(System.currentTimeMillis()).take(MAX_ORIGIN_BATCH)) {
                val currentNow = System.currentTimeMillis()
                if (isExpired(message.createdAt, currentNow)) {
                    repository.markFailed(message.messageId, null, "DELIVERY_EXPIRED")
                } else {
                    repository.requeueIfInFlight(
                        message.messageId,
                        currentNow,
                        "MESH_DELIVERY_ACK_TIMEOUT"
                    )
                }
                worked = true
            }

            if (!worked) waitForWake(originWaitTime(System.currentTimeMillis()))
        }
    }

    private suspend fun processOutgoingMessage(message: MessageRecordEntity): Boolean {
        if (!message.senderNodeId.equals(identityStore.getIdentity().nodeId, true)) return false
        val now = System.currentTimeMillis()
        if (isExpired(message.createdAt, now)) {
            repository.markFailed(message.messageId, null, "DELIVERY_EXPIRED")
            return true
        }

        repository.recordTraceEvent(
            messageId = message.messageId,
            eventType = "QUEUE_PROCESSING",
            resultCode = "OUTGOING_ATTEMPT",
            detail = "Message selected from durable queue"
        )
        val peers = MeshForwardingPolicy.select(
            peers = peerDirectory.snapshot.value,
            localNodeId = identityStore.getIdentity().nodeId,
            destinationNodeId = message.recipientNodeId,
            excludeNodeId = null,
            excludeAddress = null,
            packetId = message.messageId,
            isTransportUsable = ::hasCompatibleTransport
        )
        if (peers.isEmpty()) {
            repository.deferQueuedMessage(
                message.messageId,
                now + NO_PEER_RETRY_MS,
                "NO_MESH_NEIGHBOR"
            )
            return true
        }

        if (!repository.claimForDelivery(message.messageId, now + ORIGIN_ACK_TIMEOUT_MS)) return false
        val packet = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(message.messageId),
            messageId = message.messageId,
            originNodeId = message.senderNodeId,
            destinationNodeId = message.recipientNodeId,
            createdAt = message.createdAt,
            expiresAt = minOf(message.createdAt + MESSAGE_LIFETIME_MS, now + MESSAGE_LIFETIME_MS),
            ttl = INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = message.messageType,
            content = message.content,
            // At the origin, an empty prefix is a complete observation: no links
            // have occurred before the first transmission. Any legacy hop clears it.
            routeTransportCodes = emptyList(),
            routeTraceComplete = true
        )

        val sendStartedAt = System.nanoTime()
        val outcome = sendPacketToPeers(packet, peers)
        val durationMs = ((System.nanoTime() - sendStartedAt) / 1_000_000L).coerceAtLeast(0L)
        _snapshot.value = _snapshot.value.copy(
            lastTransportSendDurationMs = durationMs,
            updatedAt = System.currentTimeMillis()
        )
        if (outcome.accepted) {
            outcome.transportName?.let { repository.updateLastTransport(message.messageId, it) }
        } else {
            repository.requeueIfInFlight(
                message.messageId,
                System.currentTimeMillis() + originRetryDelay(message.attemptCount),
                "MESH_TRANSPORT_FAILED"
            )
        }
        return true
    }

    private suspend fun forwardingLoop() {
        while (currentCoroutineContext().isActive && started) {
            if (meshTransports.none { it.isAvailable() }) {
                waitForWake(5_000L)
                continue
            }
            val now = System.currentTimeMillis()
            val candidates = repository.getPendingForwards(now, MAX_FORWARD_BATCH)
            val worked = if (candidates.isEmpty()) {
                false
            } else {
                // Several independent packets can make progress concurrently, but
                // the semaphore bounds per-device database/radio pressure.
                coroutineScope {
                    candidates.map { record ->
                        async { forwardingSlots.withPermit { processForwardRecord(record, now) } }
                    }.awaitAll().any { it }
                }
            }

            if (!worked) waitForWake(forwardWaitTime(now))
        }
    }

    private suspend fun processForwardRecord(
        record: MeshForwardingRecordEntity,
        now: Long
    ): Boolean {
        if (record.expiresAt <= now) {
            repository.markForwardExpired(record.packetId)
            receivedAtByPacket.remove(record.packetId)
            return true
        }
        if (!repository.claimForward(record.packetId)) return false

        // Candidate rows may be stale: a better duplicate can update the durable
        // forwarding record after the pending batch was queried but before claim.
        // Reload only after the atomic claim so the selected predecessor, hop count,
        // and route trace are read from the same durable row.
        val claimedRecord = repository.getForwarding(record.packetId)
        if (claimedRecord == null || claimedRecord.state != "FORWARDING") return false
        val activeRecord = claimedRecord
        if (activeRecord.expiresAt <= System.currentTimeMillis()) {
            repository.markForwardExpired(activeRecord.packetId)
            receivedAtByPacket.remove(activeRecord.packetId)
            return true
        }

        val packet = activeRecord.toPacket()
        if (packet == null) {
            repository.markForwardInvalid(activeRecord.packetId)
            receivedAtByPacket.remove(activeRecord.packetId)
            return true
        }
        if (packet.ttl <= 0) {
            repository.markForwardExpired(activeRecord.packetId)
            receivedAtByPacket.remove(activeRecord.packetId)
            return true
        }

        val peers = MeshForwardingPolicy.select(
            peers = peerDirectory.snapshot.value,
            localNodeId = identityStore.getIdentity().nodeId,
            destinationNodeId = packet.destinationNodeId,
            excludeNodeId = activeRecord.receivedFromNodeId,
            excludeAddress = activeRecord.receivedFromAddress,
            packetId = activeRecord.packetId,
            isTransportUsable = ::hasCompatibleTransport
        )

        if (peers.isEmpty()) {
            repository.scheduleForwardRetry(
                activeRecord.packetId,
                now + FORWARD_NO_PEER_RETRY_MS,
                "NO_MESH_NEIGHBOR"
            )
            return true
        }

        // The durable record already supplies the single suppression window.
        val receivedAt = receivedAtByPacket[activeRecord.packetId]
        val queueDelay = receivedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        if (queueDelay != null) {
            _snapshot.value = _snapshot.value.copy(
                lastForwardQueueDelayMs = queueDelay,
                updatedAt = System.currentTimeMillis()
            )
        }

        val forwardedPacket = packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
        val sendStartedAt = System.nanoTime()
        val sendOutcome = sendPacketToPeers(forwardedPacket, peers)
        val sendDurationMs = ((System.nanoTime() - sendStartedAt) / 1_000_000L).coerceAtLeast(0L)
        _snapshot.value = _snapshot.value.copy(
            lastTransportSendDurationMs = sendDurationMs,
            updatedAt = System.currentTimeMillis()
        )

        return if (sendOutcome.accepted) {
            val finalized = repository.markForwarded(activeRecord.packetId)
            if (finalized == 0) {
                // A better duplicate may have arrived while the radio send was in
                // progress. The DAO refuses to mark that row FORWARDED while its
                // dirty marker is set; put it back on the queue for the better path.
                repository.requeueAfterBetterCopy(activeRecord.packetId, System.currentTimeMillis())
                wakeChannel.trySend(Unit)
            } else {
                receivedAtByPacket.remove(activeRecord.packetId)
            }
            true
        } else {
            repository.scheduleForwardRetry(
                activeRecord.packetId,
                System.currentTimeMillis() + forwardRetryDelay(activeRecord.attemptCount),
                "MESH_TRANSPORT_FAILED"
            )
            true
        }
    }

    private suspend fun handleReceivedPacket(
        payload: ByteArray,
        sourceNodeId: String?,
        sourceAddress: String,
        sourceTransport: MeshTransportKind
    ): Boolean {
        val packet = MeshPacketCodec.decode(payload) ?: return false
        val now = System.currentTimeMillis()

        if (packet.expiresAt <= now) return false
        if (packet.createdAt > now + MAX_CLOCK_SKEW_MS) return false
        if (packet.ttl !in 0..MAX_TTL) return false
        if (packet.hopCount !in 0..MAX_TTL) return false

        val localNodeId = identityStore.getIdentity().nodeId
        if (packet.originNodeId.equals(localNodeId, true)) {
            // A structurally valid packet looped back to its origin is already
            // consumed. Accept it at the transport layer to prevent retries, but
            // still reject application packet types this node would never process.
            return when (packet.packetType) {
                MeshPacket.PacketType.DATA -> packet.messageType in APPLICATION_MESSAGE_TYPES
                MeshPacket.PacketType.DELIVERY_ACK ->
                    packet.messageType == DELIVERY_ACK_TYPE && packet.deliveryHopCount in 0..MAX_TTL
            }
        }

        return when (packet.packetType) {
            MeshPacket.PacketType.DATA -> {
                if (packet.messageType !in APPLICATION_MESSAGE_TYPES) return false
                if (packet.destinationNodeId.equals(localNodeId, true)) {
                    handleDataForLocalDestination(packet, now, sourceTransport, sourceNodeId)
                } else if (packet.ttl > 0 && packet.hopCount < MAX_TTL) {
                    acceptForRelay(packet, sourceNodeId, sourceAddress, sourceTransport, now)
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
                    acceptForRelay(packet, sourceNodeId, sourceAddress, sourceTransport, now)
                } else {
                    false
                }
            }
        }
    }

    private suspend fun acceptForRelay(
        packet: MeshPacket,
        predecessorNodeId: String?,
        sourceAddress: String,
        sourceTransport: MeshTransportKind,
        now: Long
    ): Boolean {
        val packetWithObservedHop = if (packet.packetType == MeshPacket.PacketType.DATA) {
            packet.copy(routeTransportCodes = (packet.routeTransportCodes + sourceTransport.traceCode).take(MeshPacketCodec.INITIAL_TTL + 1))
        } else packet
        repository.recordTraceEvent(
            messageId = packet.messageId,
            packetId = packet.packetId,
            eventType = if (packet.packetType == MeshPacket.PacketType.DATA) "PACKET_RECEIVED_FOR_RELAY" else "ACK_RECEIVED_FOR_RELAY",
            transport = sourceTransport.persistedName,
            peerNodeId = predecessorNodeId,
            resultCode = "ACCEPTED"
        )

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
                    // Keep the route metadata paired with the predecessor/hop count
                    // selected by the duplicate-suppression policy. Otherwise a better
                    // copy can be forwarded with the older copy's transport sequence.
                    routeTrace = packetWithObservedHop.routeTransportCodes.joinToString(","),
                    routeTraceComplete = packetWithObservedHop.routeTraceComplete,
                    nextAttemptAt = now + FORWARD_SUPPRESSION_RECHECK_MS
                )
                wakeChannel.trySend(Unit)
            }

            // Receiving the same packet more than once is still an accepted
            // transport event. More importantly, repair an inconsistent state
            // where the cache survived but the durable forwarding row did not.
            if (repository.getForwarding(packet.packetId) != null) return true

            val rebuilt = repository.insertForwardingRecord(
                packetWithObservedHop.toForwardingRecord(predecessorNodeId, sourceAddress)
            )
            if (rebuilt) wakeChannel.trySend(Unit)
            return rebuilt
        }

        val accepted = repository.acceptRelayPacket(
            seen = packetWithObservedHop.toSeenPacket(now),
            forwardingRecord = packetWithObservedHop.toForwardingRecord(
                predecessor = predecessorNodeId,
                predecessorAddress = sourceAddress
            )
        )
        if (accepted) {
            receivedAtByPacket.putIfAbsent(packet.packetId, now)
            if (receivedAtByPacket.size > MAX_TRACKED_RECEIVE_TIMESTAMPS) {
                val oldest = receivedAtByPacket.entries
                    .sortedBy { it.value }
                    .take(receivedAtByPacket.size - MAX_TRACKED_RECEIVE_TIMESTAMPS / 2)
                oldest.forEach { receivedAtByPacket.remove(it.key, it.value) }
            }
            wakeChannel.trySend(Unit)
        }
        return accepted
    }

    private suspend fun handleDataForLocalDestination(
        packet: MeshPacket,
        now: Long,
        sourceTransport: MeshTransportKind,
        immediatePeerNodeId: String?
    ): Boolean {
        val record = MessageRecordEntity(
            messageId = packet.messageId,
            senderNodeId = packet.originNodeId,
            recipientNodeId = packet.destinationNodeId,
            content = packet.content,
            createdAt = packet.createdAt,
            messageType = packet.messageType,
            status = DeliveryStatus.DELIVERED.name,
            lastTransport = sourceTransport.persistedName,
            deliveredAt = now,
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            deliveryHopCount = packet.hopCount,
            routeTrace = (packet.routeTransportCodes + sourceTransport.traceCode).take(MeshPacketCodec.INITIAL_TTL + 1).joinToString(","),
            routeTraceComplete = packet.routeTraceComplete
        )

        val acceptance = repository.acceptDestinationPacket(
            seen = packet.toSeenPacket(now),
            record = record
        )

        if (!acceptance.messagePersisted) {
            // Never emit an application Delivery ACK unless the destination's
            // message row is durably present. The sender may safely retry.
            return false
        }

        repository.recordTraceEvent(
            messageId = packet.messageId,
            packetId = packet.packetId,
            eventType = "DESTINATION_PERSISTED",
            transport = sourceTransport.persistedName,
            peerNodeId = immediatePeerNodeId,
            resultCode = if (acceptance.messagePersisted) "PERSISTED" else "PERSIST_FAILED"
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
                deliveryHopCount = acceptance.deliveryHopCount,
                routeTransportCodes = (packet.routeTransportCodes + sourceTransport.traceCode).take(MeshPacketCodec.INITIAL_TTL + 1),
                routeTraceComplete = packet.routeTraceComplete
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
            deliveryHopCount = packet.deliveryHopCount ?: 0,
            routeTrace = packet.routeTransportCodes.joinToString(","),
            routeTraceComplete = packet.routeTraceComplete
        )

        repository.recordTraceEvent(
            messageId = packet.messageId,
            packetId = packet.packetId,
            eventType = "DELIVERY_ACK_RECEIVED",
            resultCode = if (delivered) "DELIVERED" else "IGNORED",
            detail = "Route hops=${packet.routeTransportCodes.size}"
        )
        if (delivered) {
            _snapshot.value = _snapshot.value.copy(
                lastDeliveredHopCount = packet.deliveryHopCount ?: 0,
                updatedAt = now
            )
        }
        return delivered
    }

    private data class MeshSendOutcome(
        val accepted: Boolean,
        val transportName: String? = null
    )

    private suspend fun sendPacketToPeers(
        packet: MeshPacket,
        peers: List<MeshPeer>
    ): MeshSendOutcome {
        if (runCatching { MeshPacketCodec.encode(packet) }.isFailure) return MeshSendOutcome(false)
        if (peers.isEmpty()) return MeshSendOutcome(false)

        // Forwarding policy caps fan-out at MAX_FANOUT. Start bounded peer attempts
        // independently and return as soon as one peer accepts the packet; slow
        // secondary peers may finish in the background and cannot stall the next
        // forwarding stage. If every peer fails, wait until all attempts finish
        // before scheduling a durable retry. Jobs are tracked and cancelled on stop.
        if (!started) return MeshSendOutcome(false)
        val completion = CompletableDeferred<MeshSendOutcome>()
        val remaining = AtomicInteger(peers.size)
        val attempts = ArrayList<Job>(peers.size)
        peers.forEach { peer ->
            if (!started || !fanoutAttemptSlots.tryAcquire()) {
                if (remaining.decrementAndGet() == 0) completion.complete(MeshSendOutcome(false))
                return@forEach
            }
            val attemptCompleted = AtomicBoolean(false)
            val releaseAttempt = {
                if (attemptCompleted.compareAndSet(false, true)) {
                    fanoutAttemptSlots.release()
                    if (remaining.decrementAndGet() == 0) completion.complete(MeshSendOutcome(false))
                }
            }
            val job = scope.launch {
                try {
                    val acceptedTransport = sendPacketToPeer(peer, packet)
                    if (acceptedTransport != null) {
                        completion.complete(MeshSendOutcome(true, acceptedTransport.persistedName))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One peer's failure must not cancel attempts to other peers.
                } finally {
                    releaseAttempt()
                }
            }
            attempts += job
            activeFanoutJobs.add(job)
            job.invokeOnCompletion {
                activeFanoutJobs.remove(job)
                releaseAttempt()
            }
            if (!started) job.cancel()
        }
        return try {
            completion.await()
        } catch (cancelled: CancellationException) {
            attempts.forEach(Job::cancel)
            throw cancelled
        }
    }

    private suspend fun sendPacketToPeer(
        peer: MeshPeer,
        packet: MeshPacket
    ): MeshTransportKind? {
        // Older/unknown peers receive the legacy v1 packet so ordinary messaging
        // still works. Their route metadata cannot be preserved, so the trace is
        // explicitly marked partial when the packet re-enters a v2 node.
        val supportsTrace = peer.meshProtocolVersion >= MeshPacketCodec.PROTOCOL_VERSION
        val wirePacket = if (supportsTrace) packet else packet.copy(
            routeTransportCodes = emptyList(),
            routeTraceComplete = false
        )
        val wireVersion = if (supportsTrace) MeshPacketCodec.PROTOCOL_VERSION else 1
        val bytes = runCatching { MeshPacketCodec.encode(wirePacket, wireVersion) }.getOrNull() ?: return null
        var attempt = 0
        val candidates = transportSelector.candidates(peer).filter { (transport, _) ->
            supportsTrace || transport.kind == MeshTransportKind.BLE_GATT
        }
        for ((transport, endpoint) in candidates) {
            attempt += 1
            if (attempt > 1) {
                repository.recordTraceEvent(
                    messageId = packet.messageId,
                    packetId = packet.packetId,
                    eventType = "TRANSPORT_FALLBACK",
                    transport = transport.kind.persistedName,
                    peerNodeId = peer.nodeId,
                    attemptNumber = attempt,
                    resultCode = "NEXT_CANDIDATE",
                    detail = "Previous transport attempt failed or was unavailable"
                )
            }
            val startedAt = System.nanoTime()
            repository.recordTraceEvent(
                messageId = packet.messageId,
                packetId = packet.packetId,
                eventType = "SEND_ATTEMPT_STARTED",
                transport = transport.kind.persistedName,
                peerNodeId = peer.nodeId,
                attemptNumber = attempt
            )
            val result = try {
                transportSendSlots.withPermit { transport.send(endpoint, bytes, packet.messageId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                MeshTransportSendResult.Failed(transport.kind, failure.javaClass.simpleName)
            }
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            when (result) {
                is MeshTransportSendResult.Accepted -> {
                    repository.recordTraceEvent(
                        messageId = packet.messageId,
                        packetId = packet.packetId,
                        eventType = "NEXT_HOP_ACCEPTED",
                        transport = result.transport.persistedName,
                        peerNodeId = peer.nodeId,
                        attemptNumber = attempt,
                        durationMs = durationMs,
                        resultCode = "ACCEPTED"
                    )
                    return result.transport
                }
                is MeshTransportSendResult.Unavailable -> repository.recordTraceEvent(
                    messageId = packet.messageId, packetId = packet.packetId,
                    eventType = "SEND_ATTEMPT_FAILED", transport = transport.kind.persistedName,
                    peerNodeId = peer.nodeId, attemptNumber = attempt, durationMs = durationMs,
                    resultCode = "UNAVAILABLE", detail = result.reason
                )
                is MeshTransportSendResult.Failed -> repository.recordTraceEvent(
                    messageId = packet.messageId, packetId = packet.packetId,
                    eventType = "SEND_ATTEMPT_FAILED", transport = transport.kind.persistedName,
                    peerNodeId = peer.nodeId, attemptNumber = attempt, durationMs = durationMs,
                    resultCode = "FAILED", detail = result.reason
                )
            }
        }
        return null
    }

    /** Legacy/unknown packet peers are supported only through the BLE v1 codec. */
    private fun hasCompatibleTransport(peer: MeshPeer): Boolean {
        val candidates = transportSelector.candidates(peer)
        return if (peer.meshProtocolVersion < MeshPacketCodec.PROTOCOL_VERSION) {
            candidates.any { it.first.kind == MeshTransportKind.BLE_GATT }
        } else {
            candidates.isNotEmpty()
        }
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
                availableTransports = meshTransports.filter { it.isAvailable() }.map { it.kind.name },
                wifiDirectDiscoveryActive = wifiDirectManager.isDiscoveryActive(),
                connectedWifiPeers = wifiDirectManager.connectedPeerCount(),
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

    private suspend fun forwardWaitTime(now: Long): Long {
        val next = repository.getEarliestForwardNextAttemptAt(now)
        return if (next == null) {
            FORWARD_IDLE_WAIT_MS
        } else {
            (next - now).coerceIn(50L, FORWARD_IDLE_WAIT_MS)
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
            lastError = null,
            routeTrace = routeTransportCodes.joinToString(","),
            routeTraceComplete = routeTraceComplete
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
                deliveryHopCount = deliveryHopCount,
                routeTransportCodes = routeTrace.split(",").mapNotNull { it.toIntOrNull() }.take(MeshPacketCodec.INITIAL_TTL + 1),
                routeTraceComplete = routeTraceComplete
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
        private const val WIFI_START_RETRY_MS = 15_000L
        private const val MAX_ORIGIN_BATCH = 8
        private const val MAX_CONCURRENT_ORIGIN_SENDS = 3
        private const val MAX_CONCURRENT_TRANSPORT_SENDS = 8
        private const val MAX_CONCURRENT_FANOUT_ATTEMPTS = 12
        private const val MAX_FORWARD_BATCH = 16
        private const val MAX_CONCURRENT_FORWARD_PACKETS = 4
        private const val MAX_TRACKED_RECEIVE_TIMESTAMPS = 1_024
        private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
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
