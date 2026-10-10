package com.hybridmesh.relay.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import com.hybridmesh.relay.permissions.PermissionManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withPermit

/** Runtime-only identity and endpoint learned from a completed Neyra socket handshake. */
data class WifiPeerSessionInfo(
    val nodeId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val canRelay: Boolean,
    val canStoreForward: Boolean
)

/**
 * Owns Android Wi-Fi Direct discovery/group lifecycle and the TCP sessions inside
 * the resulting P2P group. The overlay remains a mesh: a GO/client group is only
 * one physical bearer and packets are handed to MeshEngine for forwarding.
 */
class WifiDirectManager(
    context: Context,
    private val onPeerConnected: (WifiPeerSessionInfo) -> Unit,
    private val onPacket: suspend (sourceNodeId: String, payload: ByteArray) -> Boolean,
    private val onPeerDisconnected: (nodeId: String) -> Unit,
    private val onAttachmentBegin: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest) -> Long?,
    private val onAttachmentChunk: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest, offset: Long, bytes: ByteArray) -> Long?,
    private val onAttachmentFinish: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest) -> Boolean
) {
    private val appContext = context.applicationContext
    private val identityStore = IdentityStore.getInstance(appContext)
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val activeMediaSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val handshakeSlots = Semaphore(MAX_CONCURRENT_HANDSHAKES)
    private val connectAttempts = ConcurrentHashMap.newKeySet<String>()
    private val advertisedServiceDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    private val compatibleServiceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val transferIds = AtomicLong(System.nanoTime())
    private val serviceSetupInProgress = AtomicBoolean(false)
    private val serviceSetupGeneration = AtomicLong(0L)
    private val discoveryRequestInProgress = AtomicBoolean(false)
    private val connectionAttemptInProgress = AtomicBoolean(false)
    private val connectionInfoRequestInProgress = AtomicBoolean(false)
    private val connectionInfoRequestToken = AtomicLong(0L)
    private val discoveryToken = UUID.randomUUID().toString().replace("-", "").take(12)

    @Volatile private var started = false
    @Volatile private var receiverRegistered = false
    @Volatile private var groupFormed = false
    @Volatile private var groupOwner = false
    @Volatile private var groupOwnerAddress: InetAddress? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var mediaServerSocket: ServerSocket? = null
    @Volatile private var mediaServerJob: Job? = null
    @Volatile private var clientJob: Job? = null
    @Volatile private var connectionTimeoutJob: Job? = null
    @Volatile private var serviceRetryJob: Job? = null
    @Volatile private var reconciliationJob: Job? = null
    @Volatile private var connectionInfoWatchdogJob: Job? = null
    @Volatile private var discoveryRetryJob: Job? = null
    @Volatile private var serviceDiscoveryActive = false
    @Volatile private var discoveryRetryAttempt = 0
    @Volatile private var localService: WifiP2pDnsSdServiceInfo? = null
    @Volatile private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val _runtimeState = MutableStateFlow(WifiDirectRuntimeState())
    val runtimeState: StateFlow<WifiDirectRuntimeState> = _runtimeState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        resetRuntimeForP2pLoss(clearChannel = false)
                        publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_RADIO_DISABLED")
                    } else if (started) {
                        // P2P radio toggles can invalidate service registration even
                        // when the Channel object survives. Remove stale registrations
                        // where the framework still accepts calls, then reinstall once.
                        clearServiceRegistrationOnChannel()
                        resetServiceRegistrationState()
                        if (channel == null && hasRequiredPermissions()) channel = createChannel()
                        installServiceAndDiscover()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    // `isConnected == false` also occurs during CONNECTING and
                    // OBTAINING_IPADDR on some Android builds. Resetting then clears
                    // the active attempt and restarts discovery in the middle of P2P
                    // negotiation, which can make connection setup take minutes.
                    if (networkInfo?.detailedState == NetworkInfo.DetailedState.DISCONNECTED && groupFormed) {
                        resetGroupConnections()
                    }
                    requestConnectionInfo()
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (started) {
            if (hasRequiredPermissions() && channel != null) return true
            stop()
        }
        if (manager == null) return false
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return false
        if (!hasRequiredPermissions()) return false
        val identity = identityStore.getIdentity()
        if (identity.nodeId.isBlank() || identity.deviceName.isBlank() || !identityStore.isNicknameConfigured()) return false

        started = true
        publishRuntimeState(WifiDirectStage.STARTING)
        channel = createChannel()
        if (channel == null) {
            started = false
            return false
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        installServiceAndDiscover()
        requestConnectionInfo()
        reconciliationJob = scope.launch {
            while (isActive && started) {
                requestConnectionInfo()
                delay(CONNECTION_RECONCILE_INTERVAL_MS)
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!started && !receiverRegistered) return
        started = false
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        serviceSetupGeneration.incrementAndGet()
        serviceSetupInProgress.set(false)
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        serviceDiscoveryActive = false
        discoveryRetryAttempt = 0
        discoveryRequestInProgress.set(false)
        connectionInfoRequestInProgress.set(false)
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        serviceRetryJob?.cancel(); serviceRetryJob = null
        reconciliationJob?.cancel(); reconciliationJob = null
        clientJob?.cancel(); clientJob = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        closeAllSessions()
        channel?.let { current ->
            if (hasRequiredPermissions()) {
                runCatching { manager?.clearServiceRequests(current, null) }
                localService?.let { runCatching { manager?.removeLocalService(current, it, null) } }
                runCatching { manager?.removeGroup(current, null) }
            }
        }
        serviceRequest = null
        localService = null
        advertisedServiceDevices.clear()
        compatibleServiceAddresses.clear()
        connectAttempts.clear()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        channel = null
        publishRuntimeState(WifiDirectStage.STOPPED)
    }

    fun isStarted(): Boolean = started && channel != null && hasRequiredPermissions()
    fun isDiscoveryActive(): Boolean =
        isStarted() && serviceRequest != null && serviceDiscoveryActive
    fun isAvailable(): Boolean = sessions.isNotEmpty()
    fun connectedPeerCount(): Int = sessions.size
    fun hasPeer(nodeId: String): Boolean = sessions.containsKey(normalize(nodeId))

    private fun publishRuntimeState(
        stage: WifiDirectStage,
        error: String? = _runtimeState.value.lastError,
        retryCount: Int = _runtimeState.value.retryCount
    ) {
        val previous = _runtimeState.value
        val now = System.currentTimeMillis()
        _runtimeState.value = previous.copy(
            stage = stage,
            groupOwner = groupOwner,
            groupOwnerAddress = groupOwnerAddress?.hostAddress,
            controlServerReady = serverSocket != null,
            mediaServerReady = mediaServerSocket != null,
            sessionCount = sessions.size,
            retryCount = retryCount,
            lastError = error,
            stageSince = if (previous.stage == stage) previous.stageSince else now,
            updatedAt = now
        )
    }

    suspend fun sendToPeer(nodeId: String, payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > MeshPacketCodec.MAX_PACKET_BYTES) return false
        return sessions[normalize(nodeId)]?.send(payload, transferIds.incrementAndGet()) ?: false
    }

    /** Sends a file on a separate bounded TCP port; the mesh packet codec is untouched. */
    suspend fun sendAttachmentToPeer(
        nodeId: String,
        manifest: AttachmentTransferManifest,
        file: File,
        onProgress: suspend (Long) -> Unit = {}
    ): Boolean {
        if (!manifest.isValid() || !file.isFile || file.length() != manifest.sizeBytes) return false
        val peer = sessions[normalize(nodeId)]?.peer ?: return false
        val socket = Socket()
        activeMediaSockets.add(socket)
        val finished = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(MEDIA_TRANSFER_TIMEOUT_MS)
            if (!finished.get()) runCatching { socket.close() }
        }
        return try {
            withContext(Dispatchers.IO) {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = MEDIA_ACK_TIMEOUT_MS.toInt()
                socket.connect(InetSocketAddress(peer.host, MEDIA_TCP_PORT), SOCKET_CONNECT_TIMEOUT_MS)
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                output.writeInt(MEDIA_MAGIC)
                output.writeInt(MEDIA_VERSION)
                output.writeUTF(identityStore.getIdentity().nodeId)
                writeManifest(output, manifest)
                output.flush()
                val accepted = input.readBoolean()
                var offset = input.readLong()
                if (!accepted || offset !in 0..manifest.sizeBytes) return@withContext false

                file.inputStream().buffered(MEDIA_CHUNK_BYTES).use { source ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val count = source.skip(offset - skipped)
                        if (count <= 0L) return@withContext false
                        skipped += count
                    }
                    val buffer = ByteArray(MEDIA_CHUNK_BYTES)
                    while (offset < manifest.sizeBytes) {
                        val wanted = minOf(buffer.size.toLong(), manifest.sizeBytes - offset).toInt()
                        var read = 0
                        while (read < wanted) {
                            val count = source.read(buffer, read, wanted - read)
                            if (count < 0) return@withContext false
                            if (count > 0) read += count
                        }
                        output.writeInt(read)
                        output.writeLong(offset)
                        output.write(buffer, 0, read)
                        output.flush()
                        if (!input.readBoolean()) return@withContext false
                        val nextOffset = input.readLong()
                        if (nextOffset != offset + read) return@withContext false
                        offset = nextOffset
                        onProgress(offset)
                    }
                }
                output.writeInt(0)
                output.flush()
                input.readBoolean()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            finished.set(true)
            watchdog.cancel()
            runCatching { socket.close() }
            activeMediaSockets.remove(socket)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        // Keep runtime checks aligned with the permission request flow. Apps
        // targeting API 32 or lower on Android 13 still use location permission;
        // API 33+ targets use NEARBY_WIFI_DEVICES.
        return PermissionManager.wifiDirectPermissionGranted(appContext)
    }

    @SuppressLint("MissingPermission")
    private fun installServiceAndDiscover() {
        if (!started || !hasRequiredPermissions()) return
        val current = channel ?: return
        if (localService != null && serviceRequest != null) {
            publishRuntimeState(WifiDirectStage.DISCOVERING)
            beginDiscovery()
            return
        }
        if (!serviceSetupInProgress.compareAndSet(false, true)) return
        val generation = serviceSetupGeneration.incrementAndGet()
        val service = runCatching {
            WifiP2pDnsSdServiceInfo.newInstance(
                "neyra",
                "_neyra._tcp",
                mapOf(
                    "v" to MeshPacketCodec.PROTOCOL_VERSION.toString(),
                    // A per-process tie-breaker elects one phone to initiate P2P
                    // negotiation, avoiding both phones connecting simultaneously.
                    "r" to discoveryToken
                )
            )
        }.getOrNull() ?: run {
            serviceSetupInProgress.set(false)
            scheduleServiceRetry()
            return
        }
        localService = service
        val p2pManager = manager
        if (p2pManager == null) {
            localService = null
            serviceSetupInProgress.set(false)
            return
        }
        p2pManager.addLocalService(current, service, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!isCurrentSetup(current, generation)) {
                    runCatching { manager?.removeLocalService(current, service, null) }
                    return
                }
                manager?.setDnsSdResponseListeners(
                current,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
                    if (instanceName.equals("neyra", true) && device != null && started && !groupFormed) {
                        val key = device.deviceAddress.lowercase()
                        if (advertisedServiceDevices.size >= MAX_DISCOVERED_SERVICE_DEVICES) {
                            advertisedServiceDevices.clear()
                            compatibleServiceAddresses.clear()
                        }
                        advertisedServiceDevices[key] = device
                        if (key in compatibleServiceAddresses) connectToService(device)
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    // TXT carries protocol metadata only; authoritative Neyra identity
                    // is exchanged over the socket HELLO after the P2P group forms.
                    if (device != null) {
                        val key = device.deviceAddress.lowercase()
                        val txtRecord = record.orEmpty()
                        if (txtRecord["v"] == MeshPacketCodec.PROTOCOL_VERSION.toString()) {
                            if (compatibleServiceAddresses.size >= MAX_DISCOVERED_SERVICE_DEVICES) {
                                compatibleServiceAddresses.clear()
                                advertisedServiceDevices.clear()
                            }
                            val remoteToken = txtRecord["r"]
                            // New Neyra peers use a deterministic tie-breaker so
                            // exactly one side requests a connection. Peers using
                            // the earlier record format remain interoperable.
                            val shouldInitiate = remoteToken == null || discoveryToken < remoteToken
                            if (shouldInitiate) {
                                compatibleServiceAddresses.add(key)
                                advertisedServiceDevices[key]?.let { if (started && !groupFormed) connectToService(it) }
                            } else {
                                compatibleServiceAddresses.remove(key)
                            }
                        } else {
                            compatibleServiceAddresses.remove(key)
                        }
                    }
                }
            )
                val request = WifiP2pDnsSdServiceRequest.newInstance()
                serviceRequest = request
                manager?.addServiceRequest(current, request, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentSetup(current, generation)) return
                        serviceSetupInProgress.set(false)
                        publishRuntimeState(WifiDirectStage.DISCOVERING)
                        beginDiscovery()
                    }
                    override fun onFailure(reason: Int) {
                        if (isCurrentSetup(current, generation)) {
                            runCatching { manager?.clearServiceRequests(current, null) }
                            runCatching { manager?.removeLocalService(current, service, null) }
                            serviceRequest = null
                            localService = null
                            serviceSetupInProgress.set(false)
                            scheduleServiceRetry()
                        }
                    }
                })
            }
            override fun onFailure(reason: Int) {
                if (isCurrentSetup(current, generation)) {
                    runCatching { manager?.removeLocalService(current, service, null) }
                    localService = null
                    serviceRequest = null
                    serviceSetupInProgress.set(false)
                    scheduleServiceRetry()
                }
            }
        })
    }

    private fun isCurrentSetup(candidate: WifiP2pManager.Channel, generation: Long): Boolean =
        started && channel === candidate && serviceSetupGeneration.get() == generation

    private fun resetServiceRegistrationState() {
        serviceSetupGeneration.incrementAndGet()
        serviceSetupInProgress.set(false)
        serviceRetryJob?.cancel(); serviceRetryJob = null
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        discoveryRequestInProgress.set(false)
        serviceDiscoveryActive = false
        discoveryRetryAttempt = 0
        localService = null
        serviceRequest = null
        advertisedServiceDevices.clear()
        compatibleServiceAddresses.clear()
    }

    private fun createChannel(): WifiP2pManager.Channel? = runCatching {
        manager?.initialize(appContext, Looper.getMainLooper()) { onChannelDisconnected() }
    }.getOrNull()

    @Synchronized
    private fun onChannelDisconnected() {
        if (!started) return
        resetRuntimeForP2pLoss(clearChannel = true)
        if (hasRequiredPermissions()) {
            channel = createChannel()
            if (channel != null) installServiceAndDiscover() else scheduleServiceRetry()
        }
    }

    private fun resetRuntimeForP2pLoss(clearChannel: Boolean) {
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        discoveryRequestInProgress.set(false)
        serviceDiscoveryActive = false
        connectionInfoRequestInProgress.set(false)
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        clientJob?.cancel(); clientJob = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        closeAllSessions()
        connectAttempts.clear()
        if (clearChannel) {
            resetServiceRegistrationState()
            channel = null
        } else {
            // The radio is disabled; keep the old service/request handles so the
            // enabled broadcast can remove them before re-registering.
            serviceSetupGeneration.incrementAndGet()
            serviceSetupInProgress.set(false)
            serviceRetryJob?.cancel(); serviceRetryJob = null
            advertisedServiceDevices.clear()
            compatibleServiceAddresses.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearServiceRegistrationOnChannel() {
        val current = channel ?: return
        if (!hasRequiredPermissions()) return
        runCatching { manager?.clearServiceRequests(current, null) }
        localService?.let { service -> runCatching { manager?.removeLocalService(current, service, null) } }
    }

    private fun scheduleServiceRetry() {
        if (!started || groupFormed || serviceRetryJob?.isActive == true) return
        serviceRetryJob = scope.launch {
            delay(SERVICE_SETUP_RETRY_MS)
            serviceRetryJob = null
            if (started && !groupFormed && hasRequiredPermissions()) {
                if (channel == null) channel = createChannel()
                installServiceAndDiscover()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginDiscovery() {
        if (!started || groupFormed || connectionAttemptInProgress.get() || !hasRequiredPermissions()) return
        if (serviceDiscoveryActive || !discoveryRequestInProgress.compareAndSet(false, true)) return
        val current = channel
        val p2pManager = manager
        if (current == null || p2pManager == null) {
            discoveryRequestInProgress.set(false)
            scheduleDiscoveryRetry()
            return
        }

        try {
            // Android keeps service discovery active after a successful request
            // until a connection starts or a P2P group forms. Reissuing this call
            // on a timer only invites BUSY failures and does not speed up scanning.
            p2pManager.discoverServices(current, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discoveryRequestInProgress.set(false)
                    publishRuntimeState(WifiDirectStage.DISCOVERING)
                    if (started && channel === current && !groupFormed) {
                        serviceDiscoveryActive = true
                        discoveryRetryAttempt = 0
                        discoveryRetryJob?.cancel()
                        discoveryRetryJob = null
                    }
                }

                override fun onFailure(reason: Int) {
                    discoveryRequestInProgress.set(false)
                    if (started && channel === current && !groupFormed) {
                        serviceDiscoveryActive = false
                        publishRuntimeState(WifiDirectStage.DEGRADED, "DISCOVERY_FAILED_$reason")
                        scheduleDiscoveryRetry()
                    }
                }
            })
        } catch (_: Exception) {
            discoveryRequestInProgress.set(false)
            serviceDiscoveryActive = false
            scheduleDiscoveryRetry()
        }
    }

    private fun scheduleDiscoveryRetry() {
        if (!started || groupFormed || connectionAttemptInProgress.get() || discoveryRetryJob?.isActive == true) return
        val shift = discoveryRetryAttempt.coerceAtMost(4)
        val delayMs = (DISCOVERY_RETRY_BASE_MS * (1L shl shift)).coerceAtMost(DISCOVERY_RETRY_MAX_MS)
        discoveryRetryAttempt = (discoveryRetryAttempt + 1).coerceAtMost(5)
        discoveryRetryJob = scope.launch {
            delay(delayMs)
            discoveryRetryJob = null
            if (started && !groupFormed) beginDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToService(device: WifiP2pDevice) {
        if (!started || groupFormed || !hasRequiredPermissions()) return
        val key = device.deviceAddress?.lowercase() ?: return
        if (!connectionAttemptInProgress.compareAndSet(false, true)) return
        publishRuntimeState(WifiDirectStage.NEGOTIATING)
        if (!connectAttempts.add(key)) {
            connectionAttemptInProgress.set(false)
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = (identityStore.getIdentity().nodeId.hashCode().ushr(1) % 16)
        }
        val current = channel ?: run {
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            return
        }
        val p2pManager = manager ?: run {
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            return
        }
        try {
            p2pManager.connect(current, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    serviceDiscoveryActive = false
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = scope.launch {
                        delay(CONNECT_NEGOTIATION_TIMEOUT_MS)
                        if (started && !groupFormed && connectionAttemptInProgress.get()) {
                            connectAttempts.remove(key)
                            connectionAttemptInProgress.set(false)
                            val activeChannel = channel
                            if (activeChannel == null) {
                                scheduleDiscoveryRetry()
                            } else {
                                manager?.cancelConnect(activeChannel, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() = scheduleDiscoveryRetry()
                                    override fun onFailure(reason: Int) = scheduleDiscoveryRetry()
                                }) ?: scheduleDiscoveryRetry()
                            }
                        }
                    }
                }
                override fun onFailure(reason: Int) {
                    connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                    connectAttempts.remove(key)
                    connectionAttemptInProgress.set(false)
                    serviceDiscoveryActive = false
                    publishRuntimeState(WifiDirectStage.DEGRADED, "CONNECT_FAILED_$reason")
                    scheduleDiscoveryRetry()
                }
            })
        } catch (_: Exception) {
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                connectAttempts.remove(key)
                connectionAttemptInProgress.set(false)
                serviceDiscoveryActive = false
                publishRuntimeState(WifiDirectStage.DEGRADED, "CONNECT_EXCEPTION")
                scheduleDiscoveryRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (!started || !hasRequiredPermissions()) return
        val current = channel ?: return
        if (!connectionInfoRequestInProgress.compareAndSet(false, true)) return
        val requestToken = connectionInfoRequestToken.incrementAndGet()
        connectionInfoWatchdogJob?.cancel()
        connectionInfoWatchdogJob = scope.launch {
            delay(CONNECTION_INFO_CALLBACK_TIMEOUT_MS)
            if (connectionInfoRequestToken.compareAndSet(requestToken, requestToken + 1L)) {
                connectionInfoRequestInProgress.set(false)
            }
        }
        val p2pManager = manager ?: run {
            connectionInfoRequestInProgress.set(false)
            connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
            return
        }
        try {
            p2pManager.requestConnectionInfo(current) connectionInfoCallback@{ info: WifiP2pInfo ->
                if (connectionInfoRequestToken.get() != requestToken) return@connectionInfoCallback
                connectionInfoRequestInProgress.set(false)
                connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
                if (!started || channel !== current) return@connectionInfoCallback
            val ownerAddress = info.groupOwnerAddress
            if (!info.groupFormed || ownerAddress == null) {
                // A connection request can take several seconds to form its group.
                // Do not restart discovery just because an early connection-info
                // callback still reports the pre-group state.
                if (connectionAttemptInProgress.get()) return@connectionInfoCallback
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                connectAttempts.clear()
                connectionAttemptInProgress.set(false)
                serviceDiscoveryActive = false
                groupFormed = false
                groupOwner = false
                groupOwnerAddress = null
                closeServer()
                clientJob?.cancel(); clientJob = null
                closeAllSessions()
                beginDiscovery()
                return@connectionInfoCallback
            }

            val sameGroup = groupFormed &&
                groupOwner == info.isGroupOwner &&
                groupOwnerAddress?.hostAddress == ownerAddress.hostAddress
            connectAttempts.clear()
            connectionAttemptInProgress.set(false)
            connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
            serviceDiscoveryActive = false
            discoveryRetryJob?.cancel(); discoveryRetryJob = null
            groupFormed = true
            groupOwner = info.isGroupOwner
            groupOwnerAddress = ownerAddress
            if (!sameGroup) {
                publishRuntimeState(WifiDirectStage.GROUP_FORMED)
            }
            startMediaServer()
            if (info.isGroupOwner) {
                startServer()
            } else {
                closeServer()
                if (sessions.isEmpty()) {
                    publishRuntimeState(WifiDirectStage.CONNECTING_TO_GROUP_OWNER)
                    connectToGroupOwner(ownerAddress)
                }
            }
            }
        } catch (_: Exception) {
            connectionInfoRequestInProgress.set(false)
            connectionInfoRequestToken.compareAndSet(requestToken, requestToken + 1L)
            connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        }
    }

    private fun connectToGroupOwner(address: InetAddress) {
        val host = address.hostAddress ?: return
        if (clientJob?.isActive == true) return
        clientJob = scope.launch {
            var attempt = 0
            // The group owner can finish bringing up its TCP listener just after
            // Android reports the P2P group. Keep retrying while this group exists,
            // with a capped delay, rather than giving up after six attempts and
            // waiting for a connection broadcast that may never be sent again.
            while (isActive && started && groupFormed && !groupOwner && sessions.isEmpty()) {
                attempt++
                val socket = runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        connect(InetSocketAddress(host, TCP_PORT), CLIENT_SOCKET_CONNECT_TIMEOUT_MS)
                    }
                }.getOrNull()
                if (socket != null && establishSession(socket)) return@launch
                runCatching { socket?.close() }
                delay(if (attempt <= CLIENT_FAST_RETRY_COUNT) CLIENT_RETRY_DELAY_MS else CLIENT_RETRY_MAX_DELAY_MS)
            }
        }
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            publishRuntimeState(WifiDirectStage.CONTROL_SERVER_STARTING)
            var server: ServerSocket? = null
            var bindAttempt = 0
            while (isActive && started && groupFormed && groupOwner && server == null) {
                bindAttempt++
                server = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", TCP_PORT), SERVER_BACKLOG)
                    }
                }.getOrNull()
                if (server == null) {
                    publishRuntimeState(WifiDirectStage.DEGRADED, "CONTROL_SERVER_BIND_FAILED")
                    delay((SERVER_BIND_RETRY_MS * (1L shl bindAttempt.coerceAtMost(5))).coerceAtMost(SERVER_BIND_RETRY_MAX_MS))
                }
            }
            val activeServer = server ?: return@launch
            serverSocket = activeServer
            publishRuntimeState(WifiDirectStage.CONTROL_SERVER_READY)
            try {
                while (isActive && started && groupFormed && groupOwner) {
                    val socket = activeServer.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                    }
                    if (!handshakeSlots.tryAcquire()) {
                        runCatching { socket.close() }
                        activeMediaSockets.remove(socket)
                    } else {
                        launch {
                            try { establishSession(socket) } finally { handshakeSlots.release() }
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing the server socket is the normal cancellation path.
            } finally {
                runCatching { activeServer.close() }
                if (serverSocket === activeServer) serverSocket = null
            }
        }
    }

    private fun startMediaServer() {
        if (mediaServerJob?.isActive == true) return
        mediaServerJob = scope.launch {
            var activeServer: ServerSocket? = null
            var bindAttempt = 0
            while (isActive && started && groupFormed && activeServer == null) {
                bindAttempt++
                activeServer = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", MEDIA_TCP_PORT), 2)
                    }
                }.getOrNull()
                if (activeServer == null) {
                    publishRuntimeState(WifiDirectStage.DEGRADED, "MEDIA_SERVER_BIND_FAILED")
                    delay((SERVER_BIND_RETRY_MS * (1L shl bindAttempt.coerceAtMost(5))).coerceAtMost(SERVER_BIND_RETRY_MAX_MS))
                }
            }
            val server = activeServer ?: return@launch
            mediaServerSocket = server
            publishRuntimeState(_runtimeState.value.stage)
            try {
                while (isActive && started && groupFormed) {
                    val socket = server.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = MEDIA_ACK_TIMEOUT_MS.toInt()
                    }
                    activeMediaSockets.add(socket)
                    if (!handshakeSlots.tryAcquire()) {
                        runCatching { socket.close() }
                        activeMediaSockets.remove(socket)
                    } else {
                        launch {
                            try {
                                receiveAttachment(socket)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // A malformed or interrupted media socket is isolated to this transfer.
                            } finally {
                                activeMediaSockets.remove(socket)
                                handshakeSlots.release()
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // Socket closure is the regular shutdown path.
            } finally {
                runCatching { server.close() }
                if (mediaServerSocket === server) mediaServerSocket = null
            }
        }
    }

    private suspend fun receiveAttachment(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { active ->
            val input = DataInputStream(BufferedInputStream(active.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(active.getOutputStream()))
            if (input.readInt() != MEDIA_MAGIC || input.readInt() != MEDIA_VERSION) throw IOException("Unsupported media transfer")
            val senderNodeId = input.readUTF()
            val manifest = readManifest(input)
            val remotePeer = sessions[normalize(senderNodeId)]?.peer
            val remoteHost = active.inetAddress.hostAddress
            if (remotePeer == null || !remotePeer.nodeId.equals(senderNodeId, true) ||
                !remotePeer.host.equals(remoteHost, true) ||
                !manifest.senderNodeId.equals(senderNodeId, true) ||
                !manifest.isValid()) {
                output.writeBoolean(false); output.writeLong(0L); output.flush()
                return@withContext
            }
            val resumeOffsetResult: Long? =
                try {
                    onAttachmentBegin(senderNodeId, manifest)
                } catch (_: Exception) {
                    null
                }
            if (resumeOffsetResult == null || resumeOffsetResult !in 0..manifest.sizeBytes) {
                output.writeBoolean(false); output.writeLong(0L); output.flush()
                return@withContext
            }
            val resumeOffset: Long = requireNotNull(resumeOffsetResult)
            output.writeBoolean(true); output.writeLong(resumeOffset); output.flush()
            var expectedOffset: Long = resumeOffset
            while (true) {
                val chunkSize = input.readInt()
                if (chunkSize == 0) break
                if (chunkSize !in 1..MEDIA_CHUNK_BYTES) throw IOException("Invalid media chunk")
                val offset = input.readLong()
                if (offset != expectedOffset || chunkSize.toLong() > manifest.sizeBytes - expectedOffset) {
                    output.writeBoolean(false)
                    output.writeLong(expectedOffset)
                    output.flush()
                    return@withContext
                }
                val bytes = ByteArray(chunkSize)
                input.readFully(bytes)
                val nextOffset: Long? =
                    onAttachmentChunk(senderNodeId, manifest, offset, bytes)
                val receivedEnd = expectedOffset + chunkSize.toLong()
                val accepted = nextOffset != null && nextOffset == receivedEnd
                output.writeBoolean(accepted)
                output.writeLong(if (accepted) receivedEnd else expectedOffset)
                output.flush()
                if (!accepted) return@withContext
                expectedOffset = receivedEnd
            }
            val complete = expectedOffset == manifest.sizeBytes && onAttachmentFinish(senderNodeId, manifest)
            output.writeBoolean(complete)
            output.flush()
        }
    }

    private suspend fun establishSession(socket: Socket): Boolean {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = runCatching { DataInputStream(BufferedInputStream(socket.getInputStream())) }.getOrNull()
            ?: return false.also { runCatching { socket.close() } }
        val output = runCatching { DataOutputStream(BufferedOutputStream(socket.getOutputStream())) }.getOrNull()
            ?: return false.also { runCatching { socket.close() } }
        val local = identityStore.getIdentity()
        // SO_TIMEOUT only bounds reads; it does not bound a blocked socket write.
        // Keep an independent handshake watchdog active until HELLO is validated.
        val handshakeCompleted = AtomicBoolean(false)
        val handshakeWatchdog = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS.toLong())
            if (!handshakeCompleted.get()) runCatching { socket.close() }
        }
        val remote = try {
            publishRuntimeState(WifiDirectStage.HANDSHAKING)
            synchronized(output) {
                WifiDirectFrameCodec.writeHello(
                    output,
                    WifiDirectFrameCodec.Hello(
                        nodeId = local.nodeId,
                        deviceName = local.deviceName.take(MAX_DEVICE_NAME_CHARS),
                        protocolVersion = MeshPacketCodec.PROTOCOL_VERSION,
                        canRelay = true,
                        canStoreForward = true
                    )
                )
            }
            val hello = WifiDirectFrameCodec.readHello(input)
            if (!NodeIdGenerator.isValid(hello.nodeId) || hello.nodeId.equals(local.nodeId, true) ||
                hello.deviceName.isBlank() || hello.deviceName.length > MAX_DEVICE_NAME_CHARS ||
                hello.protocolVersion != MeshPacketCodec.PROTOCOL_VERSION) {
                throw IOException("Incompatible Neyra peer")
            }
            handshakeCompleted.set(true)
            WifiPeerSessionInfo(
                nodeId = hello.nodeId,
                deviceName = hello.deviceName,
                host = socket.inetAddress.hostAddress ?: "",
                port = TCP_PORT,
                protocolVersion = hello.protocolVersion,
                canRelay = hello.canRelay,
                canStoreForward = hello.canStoreForward
            )
        } catch (cancelled: CancellationException) {
            runCatching { socket.close() }
            throw cancelled
        } catch (_: Exception) {
            runCatching { socket.close() }
            return false
        } finally {
            handshakeWatchdog.cancel()
        }
        socket.soTimeout = 0
        if (!started || !groupFormed || socket.isClosed) {
            runCatching { socket.close() }
            return false
        }
        val key = normalize(remote.nodeId)
        val session = PeerSession(remote, socket, input, output)
        var previous: PeerSession? = null
        // The capacity check and insertion must be one critical section: several
        // accepted sockets can complete HELLO concurrently.
        val admitted = synchronized(sessions) {
            val existing = sessions[key]
            if (existing == null && sessions.size >= MAX_WIFI_SESSIONS) {
                false
            } else if (existing != null && !existing.peer.host.equals(remote.host, true)) {
                // A second socket must not be able to take over an active node ID
                // from a different address in this P2P group.
                false
            } else {
                previous = sessions.put(key, session)
                true
            }
        }
        if (!admitted) {
            runCatching { socket.close() }
            return false
        }
        previous?.takeIf { it !== session }?.close(notify = false)
        onPeerConnected(remote)
        publishRuntimeState(WifiDirectStage.SESSION_READY)
        session.startReader()
        return true
    }

    private inner class PeerSession(
        val peer: WifiPeerSessionInfo,
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream
    ) {
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()
        private val pendingAcks = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
        private val sendSlots = Semaphore(MAX_CONCURRENT_SENDS_PER_SESSION)
        private val packetWorkers = Semaphore(MAX_CONCURRENT_RECEIVE_PACKETS)
        @Volatile private var readerJob: Job? = null

        fun startReader() {
            readerJob = scope.launch {
                try {
                    while (isActive && !closed.get()) {
                        when (val frame = WifiDirectFrameCodec.readFrame(input)) {
                            is WifiDirectFrameCodec.Frame.Ack -> {
                                pendingAcks.remove(frame.transferId)?.complete(frame.accepted)
                            }
                            is WifiDirectFrameCodec.Frame.Data -> {
                                if (!packetWorkers.tryAcquire()) {
                                    writeAck(frame.transferId, false)
                                } else {
                                    scope.launch {
                                        try {
                                            val accepted = try {
                                                onPacket(peer.nodeId, frame.payload)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                false
                                            }
                                            writeAck(frame.transferId, accepted)
                                        } finally {
                                            packetWorkers.release()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Remote close, timeout or malformed frame terminates this session.
                } finally {
                    close(notify = true)
                }
            }
        }

        suspend fun send(payload: ByteArray, transferId: Long): Boolean = sendSlots.withPermit {
            if (closed.get()) return@withPermit false
            val deferred = CompletableDeferred<Boolean>()
            if (pendingAcks.putIfAbsent(transferId, deferred) != null) return@withPermit false
            // Socket writes can block independently of Socket.soTimeout. A watchdog
            // closes the socket at the operation deadline, which unblocks a stuck
            // write and completes every pending ACK waiter.
            val operationFinished = AtomicBoolean(false)
            val watchdog = scope.launch {
                delay(SEND_OPERATION_TIMEOUT_MS)
                // A transport ACK can arrive independently of the sender coroutine
                // resuming. Watch the whole operation, not just the ACK deferred, so
                // a stalled write can never disable its own watchdog.
                if (!operationFinished.get()) close(notify = true)
            }
            try {
                withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        if (closed.get()) throw IOException("Session closed")
                        WifiDirectFrameCodec.writeData(output, transferId, payload)
                    }
                }
                // Use withTimeoutOrNull for the ACK deadline so only this local
                // timeout is converted to a failed send. A parent/caller timeout
                // remains a CancellationException and is never swallowed here.
                val accepted = withTimeoutOrNull(TRANSPORT_ACK_TIMEOUT_MS) { deferred.await() }
                if (accepted == null) {
                    close(notify = true)
                    false
                } else {
                    accepted
                }
            } catch (cancelled: CancellationException) {
                // Cancellation alone cannot interrupt a blocking Java socket write.
                // Closing the socket is what releases the IO worker; preserve the
                // caller's cancellation after initiating that cleanup.
                close(notify = true)
                throw cancelled
            } catch (_: Exception) {
                close(notify = true)
                false
            } finally {
                operationFinished.set(true)
                watchdog.cancel()
                pendingAcks.remove(transferId, deferred)
            }
        }

        private fun writeAck(transferId: Long, accepted: Boolean) {
            // Unlike reads, Java socket writes have no SO_TIMEOUT. If the remote
            // peer stops reading, this independent watchdog closes the socket to
            // release a blocked write (or a writer waiting for writeLock).
            val watchdog = scope.launch {
                delay(ACK_WRITE_TIMEOUT_MS)
                close(notify = true)
            }
            try {
                synchronized(writeLock) {
                    if (closed.get()) throw IOException("Session closed")
                    WifiDirectFrameCodec.writeAck(output, transferId, accepted)
                }
            } catch (_: Exception) {
                close(notify = true)
            } finally {
                watchdog.cancel()
            }
        }

        fun close(notify: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            readerJob?.cancel()
            pendingAcks.values.forEach { it.complete(false) }
            pendingAcks.clear()
            sessions.remove(normalize(peer.nodeId), this)
            if (notify) onPeerDisconnected(peer.nodeId)
            publishRuntimeState(if (sessions.isEmpty()) WifiDirectStage.DEGRADED else WifiDirectStage.SESSION_READY)
            if (started && groupFormed && !groupOwner && sessions.isEmpty()) {
                val ownerAddress = groupOwnerAddress
                if (ownerAddress != null) {
                    scope.launch {
                        delay(CLIENT_RECONNECT_DELAY_MS)
                        if (started && groupFormed && !groupOwner && sessions.isEmpty()) {
                            connectToGroupOwner(ownerAddress)
                        }
                    }
                }
            }
        }
    }

    private fun resetGroupConnections() {
        connectAttempts.clear()
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoRequestInProgress.set(false)
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        serviceDiscoveryActive = false
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        clientJob?.cancel(); clientJob = null
        closeAllSessions()
        if (started && hasRequiredPermissions()) {
            if (localService == null || serviceRequest == null) installServiceAndDiscover()
            else beginDiscovery()
        }
    }

    private fun closeServer() {
        serverJob?.cancel(); serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun closeMediaServer() {
        mediaServerJob?.cancel(); mediaServerJob = null
        runCatching { mediaServerSocket?.close() }
        mediaServerSocket = null
    }

    private fun closeActiveMediaSockets() {
        activeMediaSockets.toList().forEach { socket -> runCatching { socket.close() } }
        activeMediaSockets.clear()
    }

    private fun closeAllSessions() {
        sessions.values.toList().forEach { it.close(notify = true) }
    }

    private fun writeManifest(output: DataOutputStream, manifest: AttachmentTransferManifest) {
        output.writeUTF(manifest.messageId)
        output.writeUTF(manifest.senderNodeId)
        output.writeUTF(manifest.recipientNodeId)
        output.writeUTF(manifest.mimeType)
        output.writeUTF(manifest.displayName)
        output.writeLong(manifest.sizeBytes)
        output.writeUTF(manifest.sha256)
        output.writeLong(manifest.createdAt)
        output.writeInt(manifest.hopCount)
    }

    private fun readManifest(input: DataInputStream): AttachmentTransferManifest =
        AttachmentTransferManifest(
            messageId = input.readUTF(),
            senderNodeId = input.readUTF(),
            recipientNodeId = input.readUTF(),
            mimeType = input.readUTF(),
            displayName = input.readUTF(),
            sizeBytes = input.readLong(),
            sha256 = input.readUTF(),
            createdAt = input.readLong(),
            hopCount = input.readInt()
        )

    private fun normalize(nodeId: String): String = nodeId.trim().lowercase()

    private fun actionListener(successAction: () -> Unit): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = successAction()
            override fun onFailure(reason: Int) = Unit
        }

    companion object {
        const val TCP_PORT = 38_991
        private const val MEDIA_TCP_PORT = TCP_PORT + 1
        private const val MEDIA_MAGIC = 0x4E594D31 // NYM1
        private const val MEDIA_VERSION = 1
        private const val MEDIA_CHUNK_BYTES = 64 * 1024
        private const val MEDIA_ACK_TIMEOUT_MS = 60_000L
        private const val MEDIA_TRANSFER_TIMEOUT_MS = 15 * 60 * 1000L
        private const val DISCOVERY_RETRY_BASE_MS = 3_000L
        private const val DISCOVERY_RETRY_MAX_MS = 30_000L
        private const val SERVICE_SETUP_RETRY_MS = 4_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
        private const val CLIENT_SOCKET_CONNECT_TIMEOUT_MS = 1_200
        private const val CONNECT_NEGOTIATION_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val TRANSPORT_ACK_TIMEOUT_MS = 8_000L
        private const val SEND_OPERATION_TIMEOUT_MS = 10_000L
        private const val ACK_WRITE_TIMEOUT_MS = 5_000L
        private const val MAX_CONCURRENT_SENDS_PER_SESSION = 2
        private const val CLIENT_RETRY_DELAY_MS = 800L
        private const val CLIENT_FAST_RETRY_COUNT = 6
        private const val CLIENT_RETRY_MAX_DELAY_MS = 5_000L
        private const val CLIENT_RECONNECT_DELAY_MS = 750L
        private const val SERVER_BIND_ATTEMPTS = 5
        private const val SERVER_BIND_RETRY_MS = 500L
        private const val SERVER_BIND_RETRY_MAX_MS = 10_000L
        private const val CONNECTION_RECONCILE_INTERVAL_MS = 2_000L
        private const val CONNECTION_INFO_CALLBACK_TIMEOUT_MS = 2_500L
        private const val SERVER_BACKLOG = 8
        private const val MAX_DEVICE_NAME_CHARS = 64
        private const val MAX_CONCURRENT_RECEIVE_PACKETS = 4
        private const val MAX_CONCURRENT_HANDSHAKES = 4
        private const val MAX_WIFI_SESSIONS = 8
        private const val MAX_DISCOVERED_SERVICE_DEVICES = 64
    }
}
