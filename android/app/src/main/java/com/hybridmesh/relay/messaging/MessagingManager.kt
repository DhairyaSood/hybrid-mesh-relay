package com.hybridmesh.relay.messaging

import android.bluetooth.BluetoothManager
import android.content.Context
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.ble.BleGattServer
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.mesh.MeshEngine
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Coordinates the long-lived messaging runtime while MeshEngine owns delivery. */
class MessagingManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val networkManager = NetworkManager.getInstance(appContext)
    private val repository = MessagingRepository.getInstance(appContext)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val gattServer = BleGattServer(appContext)
    private val identityGattClient = BleGattClient(appContext)
    private val meshEngine = MeshEngine.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val identitySyncInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastSuccessfulAdvertisedIdentity = ConcurrentHashMap<String, String>()
    private val identitySyncAttempts = ConcurrentHashMap<String, Int>()
    private val identitySyncNextAt = ConcurrentHashMap<String, Long>()

    private val _transport = MutableStateFlow(GattTransportSnapshot())
    val transport: StateFlow<GattTransportSnapshot> = _transport.asStateFlow()

    val mesh = meshEngine.snapshot

    private var started = false
    private val jobs = mutableListOf<Job>()
    @Volatile private var startupResetCompleted: CompletableDeferred<Boolean>? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true

        val resetGate = CompletableDeferred<Boolean>()
        startupResetCompleted = resetGate

        gattServer.setPacketReceiver { device, payload ->
            meshEngine.onTransportPacket(device, payload)
        }

        jobs += scope.launch {
            var success = false
            try {
                repository.resetInFlightMessages()
                repository.resetStaleForwarding()
                success = true
            } finally {
                resetGate.complete(success)
            }

            if (success && started) {
                meshEngine.start()
            }
        }

        jobs += scope.launch {
            meshEngine.transport.collect { _transport.value = it }
        }

        jobs += scope.launch {
            networkManager.runtimeEvents.collect {
                if (it == com.hybridmesh.relay.network.BleRuntimeEvent.TRANSPORT_UNAVAILABLE) {
                    repository.resetInFlightMessages()
                    repository.requeueForwardingOnTransportLoss()
                    _transport.value = GattTransportSnapshot()
                }
            }
        }

        jobs += scope.launch {
            networkManager.state.collect { state ->
                state.peers.forEach { repository.updateDiscoveredPeer(it) }
            }
        }

        jobs += scope.launch {
            networkManager.state
                .map { state ->
                    state.peers
                        .map { peer -> PeerPresence(peer.nodeId, peer.address, peer.deviceName, peer.deviceType.name) }
                        .sortedBy { it.nodeId }
                }
                .distinctUntilChanged()
                .collect { peers ->
                    peers.forEach { peer ->
                        repository.makeRecipientEligible(peer.nodeId)
                        syncPeerIdentityIfNeeded(peer.nodeId, peer.address, peer.deviceName)
                    }
                }
        }

        jobs += scope.launch { identitySyncLoop() }
    }

    fun isGattServerReady(): Boolean = gattServer.isReady

    suspend fun prepareGattServer(timeoutMs: Long = 5_000L): Boolean {
        val state = networkManager.state.value
        val ready = state.bleSupported &&
            state.permissionsGranted &&
            state.bluetoothState == BluetoothState.ON
        if (!ready) return false

        if (!gattServer.start()) {
            networkManager.setGattServerReady(false)
            return false
        }
        val serverReady = gattServer.awaitReady(timeoutMs)
        val actuallyReady = serverReady && gattServer.isReady
        if (!actuallyReady) gattServer.stop()
        networkManager.setGattServerReady(actuallyReady)
        return actuallyReady
    }

    fun stopGattServer() {
        gattServer.stop()
        networkManager.setGattServerReady(false)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        jobs.forEach(Job::cancel)
        jobs.clear()
        gattServer.setPacketReceiver(null)
        gattServer.stop()
        meshEngine.stop()
        networkManager.setGattServerReady(false)
        _transport.value = GattTransportSnapshot()
    }

    private suspend fun identitySyncLoop() {
        val resetGate = startupResetCompleted ?: return
        if (!resetGate.await()) return

        while (scope.coroutineContext.isActive && started) {
            val state = networkManager.state.value
            if (state.gattServerReady) {
                state.peers.forEach { peer ->
                    syncPeerIdentityIfNeeded(peer.nodeId, peer.address, peer.deviceName)
                }
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

            val lock = identitySyncMutex(nodeId)
            lock.lock()
            try {
                val currentNow = System.currentTimeMillis()
                val currentPreviousKey = lastSuccessfulAdvertisedIdentity[nodeId]
                val currentRetryAt = identitySyncNextAt[nodeId] ?: 0L
                if (currentPreviousKey == identityKey || currentNow < currentRetryAt) return

                val device = runCatching { bluetoothManager?.adapter?.getRemoteDevice(address) }.getOrNull()
                    ?: run {
                        recordIdentitySyncFailure(nodeId)
                        return
                    }

                val generation = networkManager.runtimeGeneration.value
                if (generation != networkManager.state.value.runtimeGeneration) return

                val identity = identityGattClient.readIdentity(
                    device = device,
                    runtimeGeneration = generation,
                    isRuntimeGenerationCurrent = {
                        networkManager.runtimeGeneration.value == generation &&
                            networkManager.state.value.bluetoothState == BluetoothState.ON &&
                            networkManager.state.value.gattServerReady
                    }
                ) ?: run {
                    recordIdentitySyncFailure(nodeId)
                    return
                }

                repository.updatePeerIdentity(nodeId, identity.displayName, identity.deviceType)
                lastSuccessfulAdvertisedIdentity[nodeId] = identityKey
                identitySyncAttempts.remove(nodeId)
                identitySyncNextAt.remove(nodeId)
            } finally {
                lock.unlock()
            }
        } finally {
            identitySyncInFlight.remove(nodeId)
        }
    }

    private fun recordIdentitySyncFailure(nodeId: String) {
        val attempt = (identitySyncAttempts[nodeId] ?: 0) + 1
        identitySyncAttempts[nodeId] = attempt
        val delayMs = minOf(300_000L, 15_000L * (1L shl (attempt - 1).coerceAtMost(4)))
        identitySyncNextAt[nodeId] = System.currentTimeMillis() + delayMs
    }

    private fun identitySyncMutex(nodeId: String): Mutex =
        identitySyncLocks.computeIfAbsent(nodeId) { Mutex() }

    private data class PeerPresence(
        val nodeId: String,
        val address: String,
        val deviceName: String,
        val deviceType: String
    )

    companion object {
        private val identitySyncLocks = ConcurrentHashMap<String, Mutex>()

        @Volatile private var INSTANCE: MessagingManager? = null

        fun getInstance(context: Context): MessagingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessagingManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
