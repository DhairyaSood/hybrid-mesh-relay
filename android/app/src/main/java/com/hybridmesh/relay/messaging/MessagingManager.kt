package com.hybridmesh.relay.messaging

import android.bluetooth.BluetoothManager
import android.content.Context
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.ble.BleGattConstants
import com.hybridmesh.relay.messaging.ble.BleGattServer
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.model.GattTransportError
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

class MessagingManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val networkManager = NetworkManager.getInstance(appContext)
    private val repository = MessagingRepository.getInstance(appContext)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val gattServer = BleGattServer(appContext)
    private val gattClient = BleGattClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val peerLocks = ConcurrentHashMap<String, Mutex>()
    private val identitySyncInFlight = ConcurrentHashMap.newKeySet<String>()
    // Successful identity sync is tied to node + address + advertised name so a
    // rediscovered node on a new address is revalidated even if its name is unchanged.
    private val lastSuccessfulAdvertisedIdentity = ConcurrentHashMap<String, String>()
    private val identitySyncAttempts = ConcurrentHashMap<String, Int>()
    private val identitySyncNextAt = ConcurrentHashMap<String, Long>()

    private val _transport = MutableStateFlow(GattTransportSnapshot())
    val transport: StateFlow<GattTransportSnapshot> = _transport.asStateFlow()

    private var started = false
    private val jobs = mutableListOf<Job>()

    @Synchronized
    fun start() {
        if (started) return
        started = true

        jobs += scope.launch {
            repository.resetInFlightMessages()
            wakeChannel.trySend(Unit)
        }

        jobs += scope.launch {
            networkManager.state
                .distinctUntilChanged { old, new ->
                    old.bluetoothState == new.bluetoothState &&
                        old.permissionsGranted == new.permissionsGranted &&
                        old.bleSupported == new.bleSupported
                }
                .collect { state ->
                    val ready = state.bleSupported &&
                        state.permissionsGranted &&
                        state.bluetoothState == BluetoothState.ON

                    if (ready) {
                        gattServer.start()
                        if (gattServer.isReady) {
                            repository.makeAllQueuedEligible()
                            networkManager.refresh()
                        } else {
                            scope.launch {
                                if (gattServer.awaitReady(BleGattConstants.SERVER_READY_TIMEOUT_MS)) {
                                    repository.makeAllQueuedEligible()
                                    networkManager.refresh()
                                    wakeChannel.trySend(Unit)
                                } else {
                                    networkManager.refresh()
                                }
                            }
                        }
                    } else {
                        gattServer.stop()
                        networkManager.refresh()
                    }
                    wakeChannel.trySend(Unit)
                }
        }

        jobs += scope.launch {
            networkManager.state
                .collect { state ->
                    state.peers.forEach { peer -> repository.updateDiscoveredPeer(peer) }
                }
        }

        jobs += scope.launch {
            networkManager.state
                .map { state ->
                    state.peers
                        .map { peer ->
                            PeerPresence(
                                nodeId = peer.nodeId,
                                address = peer.address,
                                deviceName = peer.deviceName,
                                deviceType = peer.deviceType.name
                            )
                        }
                        .sortedBy { it.nodeId }
                }
                .distinctUntilChanged()
                .collect { peers ->
                    peers.forEach { peer ->
                        repository.makeRecipientEligible(peer.nodeId)
                        syncPeerIdentityIfNeeded(
                            nodeId = peer.nodeId,
                            address = peer.address,
                            advertisedName = peer.deviceName
                        )
                    }
                    if (peers.isNotEmpty()) wakeChannel.trySend(Unit)
                }
        }

        jobs += scope.launch {
            repository.observeQueuedOutgoing()
                .distinctUntilChanged()
                .collect { wakeChannel.trySend(Unit) }
        }

        jobs += scope.launch { identitySyncLoop() }
        jobs += scope.launch { drainOutboxLoop() }
    }

    fun isGattServerReady(): Boolean = gattServer.isReady

    suspend fun prepareGattServer(timeoutMs: Long = 5_000L): Boolean {
        val state = networkManager.state.value
        val ready = state.bleSupported &&
            state.permissionsGranted &&
            state.bluetoothState == BluetoothState.ON

        if (!ready) return false

        if (!gattServer.start()) return false
        return gattServer.awaitReady(timeoutMs)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        jobs.forEach(Job::cancel)
        jobs.clear()
        gattServer.stop()
        _transport.value = GattTransportSnapshot()
    }

    private suspend fun drainOutboxLoop() {
        while (currentCoroutineContext().isActive && started) {
            val state = networkManager.state.value
            val ready = state.bleSupported &&
                state.permissionsGranted &&
                state.bluetoothState == BluetoothState.ON

            if (!ready) {
                waitForWake(30_000L)
                continue
            }

            val now = System.currentTimeMillis()
            if (!gattServer.isReady) {
                gattServer.start()
                waitForWake(250L)
                continue
            }

            val eligible = repository.getEligibleOutgoing(now)
            var attempted = false

            for (message in eligible) {
                if (!message.senderNodeId.equals(identityStore.getIdentity().nodeId, ignoreCase = true)) {
                    continue
                }

                val peer = state.peers.firstOrNull {
                    it.nodeId.equals(message.recipientNodeId, ignoreCase = true) &&
                        it.address != "Unknown"
                }

                if (peer == null) {
                    scheduleRetry(message.messageId, message.attemptCount + 1, "PEER_UNAVAILABLE")
                    attempted = true
                    break
                }

                val device = runCatching {
                    bluetoothManager?.adapter?.getRemoteDevice(peer.address)
                }.getOrNull()

                if (device == null) {
                    scheduleRetry(message.messageId, message.attemptCount + 1, "DEVICE_UNAVAILABLE")
                    attempted = true
                    break
                }

                val lock = peerLocks.computeIfAbsent(peer.nodeId) { Mutex() }
                if (!lock.tryLock()) continue

                try {
                    if (!repository.claimForDelivery(message.messageId)) continue
                    attempted = true
                    val claimed = repository.getById(message.messageId)
                    if (claimed == null) {
                        repository.scheduleRetry(
                            messageId = message.messageId,
                            attemptCount = message.attemptCount + 1,
                            nextAttemptAt = System.currentTimeMillis() + 2_000L,
                            error = "CLAIM_READBACK_FAILED"
                        )
                    } else {
                        deliverOne(claimed, device)
                    }
                } finally {
                    lock.unlock()
                }
                break
            }

            if (attempted) continue

            val nextAt = repository.getEarliestNextAttemptAt()
            val waitMs = if (nextAt == null) {
                30_000L
            } else {
                max(250L, nextAt - System.currentTimeMillis())
            }
            waitForWake(waitMs)
        }
    }

    private suspend fun deliverOne(
        message: com.hybridmesh.relay.messaging.data.MessageRecordEntity,
        device: android.bluetooth.BluetoothDevice
    ) {
        val result = gattClient.sendMessage(
            device = device,
            record = message,
            onState = { snapshot -> _transport.value = snapshot }
        )

        when (result) {
            is BleGattClient.GattSendResult.Delivered -> {
                repository.markDelivered(message.messageId, "BLE")
            }

            is BleGattClient.GattSendResult.Failed -> {
                _transport.value = result.snapshot
                when (result.error) {
                    GattTransportError.ACK_REJECTED,
                    GattTransportError.PROTOCOL_ERROR -> {
                        repository.markFailed(
                            messageId = message.messageId,
                            transport = "BLE",
                            error = result.error.name
                        )
                    }

                    else -> {
                        scheduleRetry(
                            messageId = message.messageId,
                            attemptCount = message.attemptCount,
                            error = result.error.name
                        )
                    }
                }
            }
        }
    }

    private suspend fun scheduleRetry(
        messageId: String,
        attemptCount: Int,
        error: String
    ) {
        val boundedAttempt = attemptCount.coerceAtLeast(1)
        val exponential = 2_000L * (1L shl (boundedAttempt - 1).coerceAtMost(5))
        val delayMs = minOf(60_000L, exponential)
        val jitter = (0L..1_000L).random()
        repository.scheduleRetry(
            messageId = messageId,
            attemptCount = boundedAttempt,
            nextAttemptAt = System.currentTimeMillis() + delayMs + jitter,
            error = error
        )
    }

    private suspend fun identitySyncLoop() {
        while (currentCoroutineContext().isActive && started) {
            val peers = networkManager.state.value.peers
            peers.forEach { peer ->
                syncPeerIdentityIfNeeded(
                    nodeId = peer.nodeId,
                    address = peer.address,
                    advertisedName = peer.deviceName
                )
            }
            delay(15_000L)
        }
    }

    private suspend fun syncPeerIdentityIfNeeded(
        nodeId: String,
        address: String,
        advertisedName: String
    ) {
        if (address == "Unknown") return
        if (!identitySyncInFlight.add(nodeId)) return

        try {
            val now = System.currentTimeMillis()
            val identityKey = "$address|$advertisedName"
            val previousKey = lastSuccessfulAdvertisedIdentity[nodeId]
            val retryAt = identitySyncNextAt[nodeId] ?: 0L
            if (previousKey == identityKey || now < retryAt) return

            val lock = peerLocks.computeIfAbsent(nodeId) { Mutex() }
            lock.withLock {
                val currentNow = System.currentTimeMillis()
                val currentPreviousKey = lastSuccessfulAdvertisedIdentity[nodeId]
                val currentRetryAt = identitySyncNextAt[nodeId] ?: 0L
                if (currentPreviousKey == identityKey || currentNow < currentRetryAt) return@withLock

                val device = runCatching {
                    bluetoothManager?.adapter?.getRemoteDevice(address)
                }.getOrNull() ?: run {
                    recordIdentitySyncFailure(nodeId)
                    return@withLock
                }

                val identity = gattClient.readIdentity(device)
                if (identity == null) {
                    recordIdentitySyncFailure(nodeId)
                    return@withLock
                }

                repository.updatePeerIdentity(
                    nodeId = nodeId,
                    displayName = identity.displayName,
                    deviceType = identity.deviceType
                )
                lastSuccessfulAdvertisedIdentity[nodeId] = identityKey
                identitySyncAttempts.remove(nodeId)
                identitySyncNextAt.remove(nodeId)
            }
        } finally {
            identitySyncInFlight.remove(nodeId)
        }
    }

    private fun recordIdentitySyncFailure(nodeId: String) {
        val attempt = (identitySyncAttempts[nodeId] ?: 0) + 1
        identitySyncAttempts[nodeId] = attempt
        val delayMs = minOf(
            300_000L,
            15_000L * (1L shl (attempt - 1).coerceAtMost(4))
        )
        identitySyncNextAt[nodeId] = System.currentTimeMillis() + delayMs
    }

    private suspend fun waitForWake(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            wakeChannel.receive()
        }
    }

    private data class PeerPresence(
        val nodeId: String,
        val address: String,
        val deviceName: String,
        val deviceType: String
    )

    companion object {
        @Volatile
        private var INSTANCE: MessagingManager? = null

        fun getInstance(context: Context): MessagingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessagingManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
