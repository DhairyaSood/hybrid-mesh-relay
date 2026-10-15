package com.hybridmesh.relay.messaging.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.isActive
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MeshSeenPacketEntity
import com.hybridmesh.relay.messaging.data.AttachmentRecordEntity
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.attachment.AttachmentDescriptor
import com.hybridmesh.relay.messaging.attachment.AttachmentFileStore
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferError
import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferStatus
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkDiagnostics
import com.hybridmesh.relay.network.NetworkEventKind
import com.hybridmesh.relay.network.MeshTransportPreferences
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val transportPreferences = MeshTransportPreferences.getInstance(appContext)
    private val networkDiagnostics = NetworkDiagnostics.getInstance()
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
        onPeerDisconnected = ::removeWifiPeer,
        onPeerHealthSample = ::onWifiPeerHealthSample,
        onAttachmentBegin = { source, manifest -> onAttachmentBegin(source, manifest) },
        onAttachmentChunk = { source, manifest, offset, bytes -> onAttachmentChunk(source, manifest, offset, bytes) },
        onAttachmentFinish = { source, manifest -> onAttachmentFinish(source, manifest) }
    )
    private val wifiTransport = WifiDirectMeshTransport(wifiDirectManager)
    private val meshTransports = listOf<MeshTransport>(bleTransport, wifiTransport)
    private val transportSelector = TransportSelector { meshTransports }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val attachmentWakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val receivedAtByPacket = ConcurrentHashMap<String, Long>()
    private val forwardingSlots = Semaphore(MAX_CONCURRENT_FORWARD_PACKETS)
    private val originSlots = Semaphore(MAX_CONCURRENT_ORIGIN_SENDS)
    private val transportSendSlots = Semaphore(MAX_CONCURRENT_TRANSPORT_SENDS)
    private val fanoutAttemptSlots = Semaphore(MAX_CONCURRENT_FANOUT_ATTEMPTS)
    private val activeFanoutJobs = ConcurrentHashMap.newKeySet<Job>()
    private val wifiRosterSendMutex = Mutex()
    private val wifiRosterRevision = AtomicLong(0L)
    @Volatile private var wifiRosterFingerprint: String? = null
    private data class AttachmentRateWindow(val startedAtNanos: Long, val initialBytes: Long)
    private val attachmentRateWindows = ConcurrentHashMap<String, AttachmentRateWindow>()

    private val _snapshot = MutableStateFlow(MeshRuntimeSnapshot())
    val snapshot: StateFlow<MeshRuntimeSnapshot> = _snapshot.asStateFlow()
    /** Direct endpoints plus currently reachable peers advertised by a Wi-Fi group owner. */
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
            bleEnabled = transportPreferences.selection.value.bleEnabled,
            wifiDirectEnabled = transportPreferences.selection.value.wifiDirectEnabled,
            updatedAt = System.currentTimeMillis()
        )
        // Optional transport: missing hardware/permission must not block BLE.
        if (transportPreferences.selection.value.wifiDirectEnabled) wifiDirectManager.start()

        jobs += scope.launch {
            repository.observeQueuedOutgoing()
                .distinctUntilChanged()
                .collect { wakeChannel.trySend(Unit) }
        }

        jobs += scope.launch {
            var previousTopology = emptyList<String>()
            networkManager.state.collect { state ->
                peerDirectory.replaceBlePeers(state.peers)
                attachmentWakeChannel.trySend(Unit)
                val topology = state.peers.map {
                    "${it.nodeId}|${it.address}|${it.meshProtocolVersion}|${it.canRelay}|${it.canStoreForward}"
                }.sorted()
                if (topology != previousTopology) {
                    previousTopology = topology
                    repository.makeAllQueuedEligible()
                    repository.makePendingForwardingEligible()
                    repository.wakePendingAttachments()
                }
                if (transportPreferences.selection.value.wifiDirectEnabled && !wifiDirectManager.isStarted()) {
                    wifiDirectManager.start()
                }
                wakeChannel.trySend(Unit)
            }
        }

        jobs += scope.launch {
            transportPreferences.selection.collect { selection ->
                if (selection.wifiDirectEnabled) wifiDirectManager.start() else wifiDirectManager.stop()
                val wifiState = wifiDirectManager.runtimeState.value
                repository.wakePendingAttachments()
                attachmentWakeChannel.trySend(Unit)
                _snapshot.value = _snapshot.value.copy(
                    bleEnabled = selection.bleEnabled,
                    wifiDirectEnabled = selection.wifiDirectEnabled,
                    availableTransports = meshTransports.filter { it.isAvailable() }.map { it.kind.name },
                    wifiDirectDiscoveryActive = selection.wifiDirectEnabled && wifiDirectManager.isDiscoveryActive(),
                    connectedWifiPeers = if (selection.wifiDirectEnabled) wifiDirectManager.connectedPeerCount() else 0,
                    wifiDirectGroupClientCount = if (selection.wifiDirectEnabled) wifiState.groupClientCount else 0,
                    wifiDirectGroupMembers = if (selection.wifiDirectEnabled) wifiState.groupMembers else emptyList(),
                    wifiDirectGroupFormed = selection.wifiDirectEnabled && wifiState.groupFormed,
                    wifiDirectGroupOwner = selection.wifiDirectEnabled && wifiState.groupOwner,
                    wifiDirectCandidates = if (selection.wifiDirectEnabled) wifiState.candidates else emptyList(),
                    wifiDirectStage = wifiState.stage.name,
                    wifiDirectCurrentAttemptId = if (selection.wifiDirectEnabled) wifiState.currentAttemptId else null,
                    wifiDirectCurrentPeer = if (selection.wifiDirectEnabled) wifiState.currentPeer else null,
                    wifiDirectRetryCount = if (selection.wifiDirectEnabled) wifiState.retryCount else 0,
                    wifiDirectStageSince = if (selection.wifiDirectEnabled) wifiState.stageSince else 0L,
                    wifiDirectControlServerReady = selection.wifiDirectEnabled && wifiState.controlServerReady,
                    wifiDirectMediaServerReady = selection.wifiDirectEnabled && wifiState.mediaServerReady,
                    wifiDirectGroupOwnerAddress = if (selection.wifiDirectEnabled) wifiState.groupOwnerAddress else null,
                    wifiDirectLastError = if (selection.wifiDirectEnabled) wifiState.lastError else null,
                    updatedAt = System.currentTimeMillis()
                )
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
                if (transportPreferences.selection.value.wifiDirectEnabled && !wifiDirectManager.isStarted()) {
                    wifiDirectManager.start()
                }
                delay(WIFI_START_RETRY_MS)
            }
        }

        jobs += scope.launch {
            while (currentCoroutineContext().isActive && started) {
                publishWifiGroupRoster()
                delay(WIFI_GROUP_ROSTER_REFRESH_MS)
            }
        }

        jobs += scope.launch { originLoop() }
        jobs += scope.launch { forwardingLoop() }
        jobs += scope.launch { attachmentLoop() }
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
        attachmentRateWindows.clear()
        val wifiState = wifiDirectManager.runtimeState.value
        _snapshot.value = _snapshot.value.copy(
            running = false,
            availableTransports = emptyList(),
            wifiDirectDiscoveryActive = false,
            connectedWifiPeers = 0,
            wifiDirectGroupClientCount = wifiState.groupClientCount,
            wifiDirectGroupMembers = wifiState.groupMembers,
            wifiDirectGroupFormed = wifiState.groupFormed,
            wifiDirectGroupOwner = wifiState.groupOwner,
            wifiDirectLocationModeEnabled = wifiState.locationModeEnabled,
            wifiDirectCandidates = wifiState.candidates,
            wifiDirectStage = wifiState.stage.name,
            wifiDirectCurrentAttemptId = wifiState.currentAttemptId,
            wifiDirectCurrentPeer = wifiState.currentPeer,
            wifiDirectRetryCount = wifiState.retryCount,
            wifiDirectStageSince = wifiState.stageSince,
            wifiDirectControlServerReady = wifiState.controlServerReady,
            wifiDirectMediaServerReady = wifiState.mediaServerReady,
            wifiDirectGroupOwnerAddress = wifiState.groupOwnerAddress,
            wifiDirectLastError = wifiState.lastError,
            attachmentTransferActive = false,
            currentAttachmentThroughputMbps = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** BLE-specific ingress adapter; core packet handling below is bearer-neutral. */
    suspend fun onBleTransportPacket(device: BluetoothDevice, payload: ByteArray): Boolean {
        val address = runCatching { device.address }.getOrDefault("Unknown")
        if (BleAttachmentFrameCodec.isFrame(payload)) {
            return onBleAttachmentFrame(address, payload) != null
        }
        val sourceNodeId = peerDirectory.findByBleAddress(address)?.nodeId
        return onTransportPacket(
            sourceNodeId = sourceNodeId,
            sourceAddress = address,
            sourceTransport = MeshTransportKind.BLE_GATT,
            payload = payload
        )
    }

    suspend fun onBleAttachmentFrame(device: BluetoothDevice, payload: ByteArray): Long? {
        val address = runCatching { device.address }.getOrDefault("Unknown")
        return onBleAttachmentFrame(address, payload)
    }

    /** BLE attachment frames are accepted one bounded block at a time and persisted before ACK. */
    private suspend fun onBleAttachmentFrame(address: String, payload: ByteArray): Long? {
        if (!started || !transportPreferences.selection.value.bleEnabled) return null
        val sourceNodeId = peerDirectory.findByBleAddress(address)?.nodeId ?: return null
        val frame = BleAttachmentFrameCodec.decode(payload) ?: return null
        return when (frame) {
            is BleAttachmentFrameCodec.Frame.Begin -> {
                if (frame.manifest.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES) return null
                onAttachmentBegin(sourceNodeId, frame.manifest, MeshTransportKind.BLE_GATT)
            }
            is BleAttachmentFrameCodec.Frame.Chunk -> {
                val record = repository.getAttachment(frame.messageId) ?: return null
                if (!record.receivedFromNodeId.equals(sourceNodeId, true)) return null
                val manifest = record.toAttachmentManifest()
                val nextOffset = onAttachmentChunk(
                    sourceNodeId,
                    manifest,
                    frame.offset,
                    frame.bytes,
                    MeshTransportKind.BLE_GATT
                ) ?: return null
                nextOffset
            }
            is BleAttachmentFrameCodec.Frame.Finish -> {
                val record = repository.getAttachment(frame.messageId) ?: return null
                if (!record.receivedFromNodeId.equals(sourceNodeId, true)) return null
                val manifest = record.toAttachmentManifest()
                if (onAttachmentFinish(sourceNodeId, manifest, MeshTransportKind.BLE_GATT)) manifest.sizeBytes else null
            }
        }
    }

    /** Shared ingress used by every bearer after its local peer context is resolved. */
    suspend fun onTransportPacket(
        sourceNodeId: String?,
        sourceAddress: String,
        sourceTransport: MeshTransportKind,
        payload: ByteArray
    ): Boolean {
        if (!started) return false
        val selection = transportPreferences.selection.value
        if (sourceTransport == MeshTransportKind.BLE_GATT && !selection.bleEnabled) return false
        if (sourceTransport == MeshTransportKind.WIFI_DIRECT && !selection.wifiDirectEnabled) return false
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
        scope.launch { repository.wakePendingAttachments() }
        attachmentWakeChannel.trySend(Unit)
        scope.launch {
            repository.updateWifiDiscoveredPeer(
                nodeId = peer.nodeId,
                displayName = peer.deviceName,
                protocolVersion = peer.protocolVersion,
                canRelay = peer.canRelay,
                canStoreForward = peer.canStoreForward
            )
        }
        scope.launch { publishWifiGroupRoster() }
        wakeChannel.trySend(Unit)
    }

    private fun onWifiPeerHealthSample(nodeId: String, durationMs: Long, success: Boolean) {
        networkDiagnostics.record(
            kind = if (success) NetworkEventKind.HEALTH_PROBE_SUCCEEDED else NetworkEventKind.HEALTH_PROBE_FAILED,
            operation = "wifi.health_probe",
            transport = MeshTransportKind.WIFI_DIRECT.persistedName,
            peerNodeId = nodeId,
            durationMs = durationMs,
            result = if (success) "ACK" else "TIMEOUT"
        )
    }

    fun removeWifiPeer(nodeId: String) {
        peerDirectory.removeWifi(nodeId)
        scope.launch { repository.wakePendingAttachments() }
        attachmentWakeChannel.trySend(Unit)
        scope.launch { publishWifiGroupRoster() }
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
        if (packet.packetType == MeshPacket.PacketType.DATA &&
            packet.messageType == WifiGroupRosterCodec.MESSAGE_TYPE) {
            return receiveWifiGroupRoster(packet, localNodeId, sourceNodeId, sourceTransport, now)
        }
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

    private suspend fun receiveWifiGroupRoster(
        packet: MeshPacket,
        localNodeId: String,
        sourceNodeId: String?,
        sourceTransport: MeshTransportKind,
        now: Long
    ): Boolean {
        val relayNodeId = sourceNodeId?.takeIf { it.isNotBlank() } ?: return false
        val groupState = wifiDirectManager.runtimeState.value
        if (sourceTransport != MeshTransportKind.WIFI_DIRECT ||
            !packet.originNodeId.equals(relayNodeId, true) ||
            !packet.destinationNodeId.equals(localNodeId, true) ||
            packet.hopCount != 0 || packet.ttl != INITIAL_TTL ||
            packet.expiresAt - packet.createdAt > WIFI_GROUP_ROSTER_PACKET_LIFETIME_MS ||
            !groupState.groupFormed || groupState.groupOwner ||
            !wifiDirectManager.hasPeer(relayNodeId)) return false

        val roster = WifiGroupRosterCodec.decode(packet.content) ?: return false
        val relayName = peerDirectory.findByNodeId(relayNodeId)?.deviceName ?: relayNodeId
        val peers = roster.peers.filterNot { it.nodeId.equals(localNodeId, true) }
        if (!peerDirectory.replaceIndirectPeers(relayNodeId, relayName, roster.revision, peers, now)) {
            // A duplicated or stale roster is still safely consumed at the TCP layer.
            return true
        }

        peers.forEach { peer ->
            repository.updateWifiDiscoveredPeer(
                nodeId = peer.nodeId,
                displayName = peer.deviceName,
                protocolVersion = MeshPacketCodec.PROTOCOL_VERSION,
                canRelay = peer.canRelay,
                canStoreForward = peer.canStoreForward,
                observedAt = now
            )
        }
        repository.wakePendingAttachments()
        attachmentWakeChannel.trySend(Unit)
        wakeChannel.trySend(Unit)
        return true
    }

    /** The group owner tells each active app session which other Neyra clients can be reached through it. */
    private suspend fun publishWifiGroupRoster() = wifiRosterSendMutex.withLock {
        val groupState = wifiDirectManager.runtimeState.value
        if (!started || !groupState.groupFormed || !groupState.groupOwner ||
            !transportPreferences.selection.value.wifiDirectEnabled) {
            wifiRosterFingerprint = null
            return@withLock
        }

        val connectedPeers = wifiDirectManager.connectedPeerSessions()
        val fingerprint = connectedPeers.joinToString("|") {
            "${it.nodeId.lowercase()}=${it.deviceName}|${it.canRelay}|${it.canStoreForward}"
        }
        if (fingerprint != wifiRosterFingerprint) {
            wifiRosterFingerprint = fingerprint
            wifiRosterRevision.incrementAndGet()
        }
        val revision = wifiRosterRevision.updateAndGet { if (it == 0L) 1L else it }
        val localNodeId = identityStore.getIdentity().nodeId
        val now = System.currentTimeMillis()

        coroutineScope {
            connectedPeers.map { recipient ->
                async {
                    val entries = connectedPeers.asSequence()
                        .filterNot { it.nodeId.equals(recipient.nodeId, true) }
                        .map {
                            WifiGroupRosterEntry(it.nodeId, it.deviceName, it.canRelay, it.canStoreForward)
                        }
                        .toList()
                    val packet = MeshPacket(
                        packetId = MeshPacketCodec.newPacketId(),
                        messageId = "HMRM-${localNodeId.removePrefix("HMR-").lowercase()}::${UUID.randomUUID()}",
                        originNodeId = localNodeId,
                        destinationNodeId = recipient.nodeId,
                        createdAt = now,
                        expiresAt = now + WIFI_GROUP_ROSTER_PACKET_LIFETIME_MS,
                        ttl = INITIAL_TTL,
                        hopCount = 0,
                        packetType = MeshPacket.PacketType.DATA,
                        messageType = WifiGroupRosterCodec.MESSAGE_TYPE,
                        content = WifiGroupRosterCodec.encode(revision, entries)
                    )
                    try {
                        wifiDirectManager.sendToPeer(recipient.nodeId, MeshPacketCodec.encode(packet))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
            }.awaitAll()
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
        val attachmentDescriptor = if (packet.messageType == MessageType.ATTACHMENT.name) {
            AttachmentDescriptor.decode(packet.content)?.takeIf { it.messageId == packet.messageId } ?: return false
        } else null
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
            record = record,
            attachment = attachmentDescriptor?.let {
                AttachmentRecordEntity(
                    messageId = it.messageId,
                    senderNodeId = packet.originNodeId,
                    recipientNodeId = packet.destinationNodeId,
                    mimeType = it.mimeType,
                    displayName = it.displayName,
                    sizeBytes = it.sizeBytes,
                    sha256 = it.sha256,
                    localPath = null,
                    status = if (it.sizeBytes <= AttachmentTransferManifest.MAX_BLE_BYTES) {
                        AttachmentTransferStatus.WAITING_LINK
                    } else AttachmentTransferStatus.WAITING_WIFI,
                    receivedFromNodeId = immediatePeerNodeId,
                    hopCount = packet.hopCount,
                    createdAt = packet.createdAt,
                    updatedAt = now
                )
            }
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
            } else if (packet.messageType == MessageType.ATTACHMENT.name) {
                "Sent an image or video"
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
        var attempt = 0
        val candidates = transportSelector.candidates(peer).filter { (transport, _) ->
            peer.protocolVersionFor(transport.kind) >= MeshPacketCodec.PROTOCOL_VERSION ||
                transport.kind == MeshTransportKind.BLE_GATT
        }
        for ((transport, endpoint) in candidates) {
            // Protocol support belongs to the endpoint, not the logical peer:
            // one identity may have a newer Wi-Fi session and a legacy BLE link.
            // Encode each fallback for the actual bearer that will carry it.
            val supportsTrace = peer.protocolVersionFor(transport.kind) >= MeshPacketCodec.PROTOCOL_VERSION
            val wirePacket = if (supportsTrace) packet else packet.copy(
                routeTransportCodes = emptyList(),
                routeTraceComplete = false
            )
            val wireVersion = if (supportsTrace) MeshPacketCodec.PROTOCOL_VERSION else 1
            val bytes = runCatching { MeshPacketCodec.encode(wirePacket, wireVersion) }.getOrNull() ?: continue
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
            val attemptId = UUID.randomUUID().toString()
            networkDiagnostics.record(
                kind = NetworkEventKind.REQUEST_STARTED,
                operation = "mesh.send",
                correlationId = packet.messageId,
                attemptId = attemptId,
                transport = transport.kind.persistedName,
                peerNodeId = peer.nodeId,
                bytes = bytes.size.toLong(),
                attempt = attempt
            )
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
                val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                networkDiagnostics.record(
                    kind = NetworkEventKind.REQUEST_FAILED,
                    operation = "mesh.send",
                    correlationId = packet.messageId,
                    attemptId = attemptId,
                    transport = transport.kind.persistedName,
                    peerNodeId = peer.nodeId,
                    durationMs = durationMs,
                    bytes = bytes.size.toLong(),
                    attempt = attempt,
                    result = "CANCELLED"
                )
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
                    networkDiagnostics.record(
                        kind = NetworkEventKind.REQUEST_SUCCEEDED,
                        operation = "mesh.send",
                        correlationId = packet.messageId,
                        attemptId = attemptId,
                        transport = result.transport.persistedName,
                        peerNodeId = peer.nodeId,
                        durationMs = durationMs,
                        bytes = bytes.size.toLong(),
                        attempt = attempt,
                        result = "ACCEPTED"
                    )
                    return result.transport
                }
                is MeshTransportSendResult.Unavailable -> {
                    repository.recordTraceEvent(
                        messageId = packet.messageId, packetId = packet.packetId,
                        eventType = "SEND_ATTEMPT_FAILED", transport = transport.kind.persistedName,
                        peerNodeId = peer.nodeId, attemptNumber = attempt, durationMs = durationMs,
                        resultCode = "UNAVAILABLE", detail = result.reason
                    )
                    networkDiagnostics.record(
                        kind = NetworkEventKind.REQUEST_UNAVAILABLE,
                        operation = "mesh.send",
                        correlationId = packet.messageId,
                        attemptId = attemptId,
                        transport = transport.kind.persistedName,
                        peerNodeId = peer.nodeId,
                        durationMs = durationMs,
                        bytes = bytes.size.toLong(),
                        attempt = attempt,
                        result = result.reason
                    )
                }
                is MeshTransportSendResult.Failed -> {
                    repository.recordTraceEvent(
                        messageId = packet.messageId, packetId = packet.packetId,
                        eventType = "SEND_ATTEMPT_FAILED", transport = transport.kind.persistedName,
                        peerNodeId = peer.nodeId, attemptNumber = attempt, durationMs = durationMs,
                        resultCode = "FAILED", detail = result.reason
                    )
                    networkDiagnostics.record(
                        kind = NetworkEventKind.REQUEST_FAILED,
                        operation = "mesh.send",
                        correlationId = packet.messageId,
                        attemptId = attemptId,
                        transport = transport.kind.persistedName,
                        peerNodeId = peer.nodeId,
                        durationMs = durationMs,
                        bytes = bytes.size.toLong(),
                        attempt = attempt,
                        result = result.reason
                    )
                }
            }
        }
        return null
    }

    /** Legacy/unknown packet peers are supported only through the BLE v1 codec. */
    private fun hasCompatibleTransport(peer: MeshPeer): Boolean {
        val candidates = transportSelector.candidates(peer)
        return candidates.any { (transport, _) ->
            peer.protocolVersionFor(transport.kind) >= MeshPacketCodec.PROTOCOL_VERSION ||
                transport.kind == MeshTransportKind.BLE_GATT
        }
    }

    /** Bulk files use Wi-Fi Direct or BLE, subject to each bearer’s attachment size limit. */
    private suspend fun attachmentLoop() {
        while (currentCoroutineContext().isActive && started) {
            val outgoing = repository.getPendingOutgoingAttachments(limit = 4)
            for (attachment in outgoing) {
                if (!started) break
                val now = System.currentTimeMillis()
                if (isExpired(attachment.createdAt, now)) {
                    repository.expireAttachment(attachment)
                    continue
                }
                if (attachment.hopCount >= AttachmentTransferManifest.MAX_HOPS) {
                    repository.updateAttachmentProgress(attachment.messageId, AttachmentTransferStatus.FAILED, attachment.transferredBytes, "ATTACHMENT_HOP_LIMIT")
                    continue
                }

                val isOrigin = attachment.senderNodeId.equals(identityStore.getIdentity().nodeId, true)
                if (isOrigin &&
                    attachment.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES &&
                    wifiDirectManager.connectedPeerCount() == 0
                ) {
                    repository.scheduleAttachmentRetry(
                        record = attachment,
                        status = AttachmentTransferStatus.WAITING_WIFI,
                        transferredBytes = attachment.transferredBytes,
                        error = AttachmentTransferError.WIFI_DIRECT_REQUIRED_FOR_LARGE_ATTACHMENT,
                        retryAt = System.currentTimeMillis() + ATTACHMENT_NO_ROUTE_RECHECK_MS,
                        countAsFailure = false
                    )
                    continue
                }
                val candidatePeers = peerDirectory.snapshot.value
                    .asSequence()
                    .filter { attachment.receivedFromNodeId.isNullOrBlank() || !it.nodeId.equals(attachment.receivedFromNodeId, true) }
                    .filter { !it.nodeId.equals(attachment.senderNodeId, true) }
                    .map { peer ->
                        val transports = buildList {
                            if (wifiDirectManager.hasPeer(peer.nodeId)) add(MeshTransportKind.WIFI_DIRECT)
                            if (attachment.sizeBytes <= AttachmentTransferManifest.MAX_BLE_BYTES &&
                                bleTransport.isAvailable() && bleTransport.canReach(peer.toTransportPeer())) {
                                add(MeshTransportKind.BLE_GATT)
                            }
                        }
                        peer to transports
                    }
                    .filter { it.second.isNotEmpty() }
                    .toList()
                val nextHopCandidate = candidatePeers.firstOrNull { it.first.nodeId.equals(attachment.recipientNodeId, true) }
                    ?: candidatePeers.firstOrNull { (peer, transports) ->
                        peer.canRelay && peer.canStoreForward && transports.any { transport ->
                            when (transport) {
                                MeshTransportKind.WIFI_DIRECT -> peer.wifiCanRelay && peer.wifiCanStoreForward
                                MeshTransportKind.BLE_GATT -> peer.bleCanRelay && peer.bleCanStoreForward
                                MeshTransportKind.LORA -> false
                            }
                        } &&
                            attachment.hopCount + 1 < AttachmentTransferManifest.MAX_HOPS
                    }
                if (nextHopCandidate == null) {
                    val waitingStatus = when {
                        !isOrigin -> AttachmentTransferStatus.RELAY_QUEUED
                        attachment.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES -> AttachmentTransferStatus.WAITING_WIFI
                        else -> AttachmentTransferStatus.WAITING_LINK
                    }
                    repository.scheduleAttachmentRetry(
                        record = attachment,
                        status = waitingStatus,
                        transferredBytes = attachment.transferredBytes,
                        error = null,
                        retryAt = System.currentTimeMillis() + ATTACHMENT_NO_ROUTE_RECHECK_MS,
                        countAsFailure = false
                    )
                    continue
                }

                val file = attachment.localPath?.let { java.io.File(it) }
                if (file == null || !file.isFile || file.length() != attachment.sizeBytes ||
                    runCatching { AttachmentFileStore.sha256(file) }.getOrNull() != attachment.sha256) {
                    repository.updateAttachmentProgress(
                        attachment.messageId,
                        AttachmentTransferStatus.FAILED,
                        attachment.transferredBytes,
                        "LOCAL_ATTACHMENT_MISSING_OR_CHANGED"
                    )
                    continue
                }
                val manifest = AttachmentTransferManifest(
                    messageId = attachment.messageId,
                    senderNodeId = attachment.senderNodeId,
                    recipientNodeId = attachment.recipientNodeId,
                    mimeType = attachment.mimeType,
                    displayName = attachment.displayName,
                    sizeBytes = attachment.sizeBytes,
                    sha256 = attachment.sha256,
                    createdAt = attachment.createdAt,
                    hopCount = attachment.hopCount + 1
                )
                repository.updateAttachmentProgress(
                    attachment.messageId,
                    AttachmentTransferStatus.SENDING,
                    attachment.transferredBytes
                )
                val nextHop = nextHopCandidate.first
                var successfulTransport: MeshTransportKind? = null
                var lastTransferError: String? = null
                var latestTransferredBytes = attachment.transferredBytes
                for (transport in nextHopCandidate.second) {
                    val transferStartedAt = System.nanoTime()
                    val attemptNumber = networkDiagnostics.snapshot.value.events.count {
                        it.operation == "mesh.attachment.send" &&
                            it.correlationId == attachment.messageId &&
                            it.kind in TERMINAL_SEND_EVENTS
                    } + 1
                    val attemptId = UUID.randomUUID().toString()
                    networkDiagnostics.record(
                        kind = NetworkEventKind.REQUEST_STARTED,
                        operation = "mesh.attachment.send",
                        correlationId = attachment.messageId,
                        attemptId = attemptId,
                        transport = transport.persistedName,
                        peerNodeId = nextHop.nodeId,
                        bytes = attachment.sizeBytes,
                        attempt = attemptNumber
                    )
                    val forwarded = when (transport) {
                        MeshTransportKind.WIFI_DIRECT -> wifiDirectManager.sendAttachmentToPeer(
                            nextHop.nodeId, manifest, file,
                            onProgress = { sent ->
                                latestTransferredBytes = sent
                                val rateWindow = attachmentRateWindows.getOrPut(attachment.messageId) {
                                    AttachmentRateWindow(System.nanoTime(), sent)
                                }
                                publishAttachmentThroughput(sent, rateWindow, active = sent < attachment.sizeBytes, transport = transport)
                                repository.updateAttachmentProgress(attachment.messageId, AttachmentTransferStatus.SENDING, sent)
                            }
                        )
                        MeshTransportKind.BLE_GATT -> bleTransport.sendAttachment(
                            nextHop.toTransportPeer(), manifest, file,
                            onProgress = { sent ->
                                latestTransferredBytes = sent
                                val rateWindow = attachmentRateWindows.getOrPut(attachment.messageId) {
                                    AttachmentRateWindow(System.nanoTime(), sent)
                                }
                                publishAttachmentThroughput(sent, rateWindow, active = sent < attachment.sizeBytes, transport = transport)
                                repository.updateAttachmentProgress(attachment.messageId, AttachmentTransferStatus.SENDING, sent)
                            }
                        )
                        MeshTransportKind.LORA -> false
                    }
                    val transferDurationMs = ((System.nanoTime() - transferStartedAt) / 1_000_000L).coerceAtLeast(0L)
                    val gattError = _transport.value
                        .takeIf { it.messageId == attachment.messageId }
                        ?.error
                    val transferError = when {
                        forwarded -> "ACCEPTED"
                        transport == MeshTransportKind.BLE_GATT && gattError != null &&
                            gattError != com.hybridmesh.relay.messaging.model.GattTransportError.NONE -> "BLE_${gattError.name}"
                        transport == MeshTransportKind.BLE_GATT -> "BLE_TRANSFER_FAILED"
                        else -> "WIFI_DIRECT_TRANSFER_FAILED"
                    }
                    networkDiagnostics.record(
                        kind = if (forwarded) NetworkEventKind.REQUEST_SUCCEEDED else NetworkEventKind.REQUEST_FAILED,
                        operation = "mesh.attachment.send",
                        correlationId = attachment.messageId,
                        attemptId = attemptId,
                        transport = transport.persistedName,
                        peerNodeId = nextHop.nodeId,
                        durationMs = transferDurationMs,
                        bytes = attachment.sizeBytes,
                        attempt = attemptNumber,
                        result = transferError
                    )
                    if (forwarded) {
                        successfulTransport = transport
                        break
                    } else {
                        lastTransferError = transferError
                    }
                }
                val completedTransport = successfulTransport
                if (completedTransport != null) {
                    publishAttachmentThroughput(
                        attachment.sizeBytes, attachmentRateWindows.remove(attachment.messageId),
                        active = false, transport = completedTransport
                    )
                    repository.markAttachmentForwarded(attachment, nextHop.nodeId.equals(attachment.recipientNodeId, true))
                } else {
                    publishAttachmentThroughput(
                        latestTransferredBytes,
                        attachmentRateWindows.remove(attachment.messageId),
                        active = false,
                        transport = nextHopCandidate.second.first()
                    )
                    val retryCount = (attachment.retryCount + 1).coerceAtMost(MAX_ATTACHMENT_RETRY_EXPONENT)
                    val retryDelay = (ATTACHMENT_FAILURE_RETRY_BASE_MS * (1L shl (retryCount - 1)))
                        .coerceAtMost(ATTACHMENT_FAILURE_RETRY_MAX_MS)
                    repository.scheduleAttachmentRetry(
                        record = attachment,
                        status = when {
                            !isOrigin -> AttachmentTransferStatus.RELAY_QUEUED
                            attachment.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES -> AttachmentTransferStatus.WAITING_WIFI
                            else -> AttachmentTransferStatus.WAITING_LINK
                        },
                        transferredBytes = latestTransferredBytes,
                        error = lastTransferError ?: ATTACHMENT_TRANSFER_INTERRUPTED,
                        retryAt = System.currentTimeMillis() + retryDelay,
                        countAsFailure = true
                    )
                }
            }
            if (outgoing.isEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(ATTACHMENT_IDLE_WAIT_MS) {
                    attachmentWakeChannel.receive()
                }
            } else {
                kotlinx.coroutines.withTimeoutOrNull(ATTACHMENT_RECHECK_MS) {
                    attachmentWakeChannel.receive()
                }
            }
        }
    }

    private suspend fun onAttachmentBegin(
        sourceNodeId: String,
        manifest: AttachmentTransferManifest,
        transport: MeshTransportKind = MeshTransportKind.WIFI_DIRECT
    ): Long? {
        val isFinalRecipient = manifest.recipientNodeId.equals(identityStore.getIdentity().nodeId, true)
        if (!manifest.isValid() ||
            (manifest.hopCount <= 1 && !manifest.senderNodeId.equals(sourceNodeId, true)) ||
            (transport == MeshTransportKind.BLE_GATT && manifest.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES) ||
            (!isFinalRecipient && manifest.hopCount >= AttachmentTransferManifest.MAX_HOPS) ||
            manifest.createdAt > System.currentTimeMillis() + MAX_CLOCK_SKEW_MS ||
            isExpired(manifest.createdAt, System.currentTimeMillis())) return null
        val resumeOffset = AttachmentFileStore.prepareReceive(appContext, manifest) ?: return null
        val finalPath = AttachmentFileStore.receivedFile(appContext, manifest.messageId).absolutePath
        if (!repository.prepareIncomingAttachment(manifest, finalPath, resumeOffset, sourceNodeId, localCanRelay = true)) return null
        attachmentRateWindows[manifest.messageId] = AttachmentRateWindow(System.nanoTime(), resumeOffset)
        return resumeOffset
    }

    private suspend fun onAttachmentChunk(
        sourceNodeId: String,
        manifest: AttachmentTransferManifest,
        offset: Long,
        bytes: ByteArray,
        transport: MeshTransportKind = MeshTransportKind.WIFI_DIRECT
    ): Long? {
        val nextOffset = AttachmentFileStore.appendChunk(
            appContext,
            manifest.messageId,
            offset,
            bytes,
            manifest.sizeBytes
        ) ?: return null
        repository.updateAttachmentProgress(
            manifest.messageId,
            AttachmentTransferStatus.RECEIVING,
            nextOffset
        )
        val rateWindow = attachmentRateWindows.getOrPut(manifest.messageId) {
            AttachmentRateWindow(System.nanoTime(), offset)
        }
        publishAttachmentThroughput(nextOffset, rateWindow, active = nextOffset < manifest.sizeBytes, transport = transport)
        return nextOffset
    }

    private suspend fun onAttachmentFinish(
        sourceNodeId: String,
        manifest: AttachmentTransferManifest,
        transport: MeshTransportKind = MeshTransportKind.WIFI_DIRECT
    ): Boolean {
        val completedFile = AttachmentFileStore.finishReceive(appContext, manifest) ?: run {
            attachmentRateWindows.remove(manifest.messageId)
            return false
        }
        val completed = repository.completeIncomingAttachment(
            manifest, completedFile.absolutePath, sourceNodeId, localCanRelay = true,
            transport = transport.persistedName
        )
        val rateWindow = attachmentRateWindows.remove(manifest.messageId)
        if (completed) {
            publishAttachmentThroughput(
                manifest.sizeBytes, rateWindow, active = false,
                transport = transport
            )
            repository.recordTraceEvent(
                messageId = manifest.messageId,
                eventType = if (manifest.recipientNodeId.equals(identityStore.getIdentity().nodeId, true)) "ATTACHMENT_DELIVERED" else "ATTACHMENT_STORED_FOR_RELAY",
                transport = transport.persistedName,
                peerNodeId = sourceNodeId,
                resultCode = "VERIFIED"
            )
        }
        return completed
    }

    private fun AttachmentRecordEntity.toAttachmentManifest() = AttachmentTransferManifest(
        messageId = messageId,
        senderNodeId = senderNodeId,
        recipientNodeId = recipientNodeId,
        mimeType = mimeType,
        displayName = displayName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        createdAt = createdAt,
        hopCount = hopCount
    )

    private fun publishAttachmentThroughput(
        bytes: Long,
        rateWindow: AttachmentRateWindow?,
        active: Boolean,
        transport: MeshTransportKind = MeshTransportKind.WIFI_DIRECT
    ) {
        if (rateWindow == null) return
        val transferredSinceStart = (bytes - rateWindow.initialBytes).coerceAtLeast(0L)
        val elapsedSeconds = ((System.nanoTime() - rateWindow.startedAtNanos).coerceAtLeast(1L)) / 1_000_000_000.0
        val mbps = if (transferredSinceStart > 0L) {
            (transferredSinceStart * 8.0 / elapsedSeconds) / 1_000_000.0
        } else null
        _snapshot.value = _snapshot.value.copy(
            currentAttachmentThroughputMbps = if (active) mbps else null,
            lastAttachmentThroughputMbps = if (active || mbps == null) _snapshot.value.lastAttachmentThroughputMbps else mbps,
            attachmentTransferActive = active,
            attachmentTransport = transport.persistedName,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun maintenanceLoop() {
        while (currentCoroutineContext().isActive && started) {
            repository.purgeMeshState()
            peerDirectory.pruneExpiredIndirectPeers(System.currentTimeMillis())
            delay(MAINTENANCE_INTERVAL_MS)
        }
    }

    private suspend fun snapshotLoop() {
        while (currentCoroutineContext().isActive && started) {
            val now = System.currentTimeMillis()
            peerDirectory.pruneExpiredIndirectPeers(now)
            _snapshot.value = _snapshot.value.copy(
                running = true,
                bleEnabled = transportPreferences.selection.value.bleEnabled,
                wifiDirectEnabled = transportPreferences.selection.value.wifiDirectEnabled,
                relayEnabled = true,
                pendingForwarding = repository.pendingForwardCount(now),
                cachedPackets = repository.activeSeenPacketCount(now),
                availableTransports = meshTransports.filter { it.isAvailable() }.map { it.kind.name },
                wifiDirectDiscoveryActive = wifiDirectManager.isDiscoveryActive(),
                connectedWifiPeers = wifiDirectManager.connectedPeerCount(),
                wifiDirectGroupClientCount = wifiDirectManager.runtimeState.value.groupClientCount,
                wifiDirectGroupMembers = wifiDirectManager.runtimeState.value.groupMembers,
                wifiDirectGroupFormed = wifiDirectManager.runtimeState.value.groupFormed,
                wifiDirectGroupOwner = wifiDirectManager.runtimeState.value.groupOwner,
                wifiDirectLocationModeEnabled = wifiDirectManager.runtimeState.value.locationModeEnabled,
                wifiDirectCandidates = wifiDirectManager.runtimeState.value.candidates,
                wifiDirectStage = wifiDirectManager.runtimeState.value.stage.name,
                wifiDirectCurrentAttemptId = wifiDirectManager.runtimeState.value.currentAttemptId,
                wifiDirectCurrentPeer = wifiDirectManager.runtimeState.value.currentPeer,
                wifiDirectRetryCount = wifiDirectManager.runtimeState.value.retryCount,
                wifiDirectStageSince = wifiDirectManager.runtimeState.value.stageSince,
                wifiDirectControlServerReady = wifiDirectManager.runtimeState.value.controlServerReady,
                wifiDirectMediaServerReady = wifiDirectManager.runtimeState.value.mediaServerReady,
                wifiDirectGroupOwnerAddress = wifiDirectManager.runtimeState.value.groupOwnerAddress,
                wifiDirectLastError = wifiDirectManager.runtimeState.value.lastError,
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
        private const val WIFI_GROUP_ROSTER_REFRESH_MS = 5_000L
        private const val WIFI_GROUP_ROSTER_PACKET_LIFETIME_MS = 30_000L
        private const val MAX_ORIGIN_BATCH = 8
        private const val MAX_CONCURRENT_ORIGIN_SENDS = 3
        private const val MAX_CONCURRENT_TRANSPORT_SENDS = 8
        private const val MAX_CONCURRENT_FANOUT_ATTEMPTS = 12
        private const val MAX_FORWARD_BATCH = 16
        private const val MAX_CONCURRENT_FORWARD_PACKETS = 4
        private const val MAX_TRACKED_RECEIVE_TIMESTAMPS = 1_024
        private const val ATTACHMENT_FAILURE_RETRY_BASE_MS = 5_000L
        private const val ATTACHMENT_FAILURE_RETRY_MAX_MS = 60_000L
        private const val ATTACHMENT_NO_ROUTE_RECHECK_MS = 5_000L
        private const val MAX_ATTACHMENT_RETRY_EXPONENT = 5
        private const val ATTACHMENT_RECHECK_MS = 1_000L
        private const val ATTACHMENT_IDLE_WAIT_MS = 3_000L
        private const val ATTACHMENT_TRANSFER_INTERRUPTED = "ATTACHMENT_TRANSFER_INTERRUPTED"
        private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
        private const val DELIVERY_ACK_TYPE = "DELIVERY_ACK"
        private val TERMINAL_SEND_EVENTS = setOf(
            NetworkEventKind.REQUEST_SUCCEEDED,
            NetworkEventKind.REQUEST_FAILED,
            NetworkEventKind.REQUEST_UNAVAILABLE
        )

        private val APPLICATION_MESSAGE_TYPES = setOf(
            MessageType.NORMAL.name,
            MessageType.PRIORITY.name,
            MessageType.EMERGENCY.name,
            MessageType.LOCATION.name,
            MessageType.ATTACHMENT.name
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
