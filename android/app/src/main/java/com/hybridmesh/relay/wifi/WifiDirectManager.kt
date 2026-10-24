package com.hybridmesh.relay.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager as AndroidLocationManager
import android.provider.Settings
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
import com.hybridmesh.relay.network.MeshTransportPreferences
import com.hybridmesh.relay.network.NetworkDiagnostics
import com.hybridmesh.relay.network.NetworkEventKind
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
import java.util.concurrent.atomic.AtomicReference
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
    private val onPeerHealthSample: (nodeId: String, durationMs: Long, success: Boolean) -> Unit,
    private val onAttachmentBegin: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest) -> Long?,
    private val onAttachmentChunk: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest, offset: Long, bytes: ByteArray) -> Long?,
    private val onAttachmentFinish: suspend (sourceNodeId: String, manifest: AttachmentTransferManifest) -> Boolean
) {
    private enum class AdvertisedGroupRole { NONE, OWNER, CLIENT }

    private val appContext = context.applicationContext
    private val identityStore = IdentityStore.getInstance(appContext)
    private val transportPreferences = MeshTransportPreferences.getInstance(appContext)
    private val diagnostics = NetworkDiagnostics.getInstance()
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = WifiDirectSessionRegistry<PeerSession>(MAX_WIFI_SESSIONS) { it.peer.host }
    private val activeMediaSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val handshakeSlots = Semaphore(MAX_CONCURRENT_HANDSHAKES)
    private val groupSessionGeneration = WifiDirectGroupSessionGeneration()
    private val connectAttempts = ConcurrentHashMap.newKeySet<String>()
    private val advertisedServiceDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    private val servicePeerAdvertisements = ConcurrentHashMap<String, WifiDirectPeerAdvertisement>()
    private val compatibleServiceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val groupClientAddresses = ConcurrentHashMap.newKeySet<String>()
    private val candidateFailureCounts = ConcurrentHashMap<String, Int>()
    private val candidateRetryAfter = ConcurrentHashMap<String, Long>()
    private val candidateRetryJobs = ConcurrentHashMap<String, Job>()
    private val connectionStarts = ConcurrentHashMap<String, Long>()
    private val peerRuntimeHistory = ConcurrentHashMap<String, WifiDirectPeerRuntimeState>()
    private val transferIds = AtomicLong(System.nanoTime())
    private val healthProbeJob = AtomicReference<Job?>(null)
    private val serviceSetupInProgress = AtomicBoolean(false)
    private val serviceSetupGeneration = AtomicLong(0L)
    private val discoveryRequestInProgress = AtomicBoolean(false)
    private val discoveryRequestGeneration = AtomicLong(0L)
    private val connectionAttemptInProgress = AtomicBoolean(false)
    private val connectionAttemptGeneration = AtomicLong(0L)
    private val connectionInfoRequestInProgress = AtomicBoolean(false)
    private val connectionInfoRequestToken = AtomicLong(0L)
    private val groupVerificationInProgress = AtomicBoolean(false)
    private val groupVerificationGeneration = AtomicLong(0L)
    private val serviceRoleUpdateInProgress = AtomicBoolean(false)
    private val serviceRoleGeneration = AtomicLong(0L)
    private val discoveryToken = UUID.randomUUID().toString().replace("-", "").take(12)
    private val serviceSetupLock = Any()
    private val discoveryLock = Any()

    @Volatile private var started = false
    @Volatile private var receiverRegistered = false
    @Volatile private var groupFormed = false
    @Volatile private var groupOwner = false
    @Volatile private var groupClientCount = 0
    @Volatile private var groupLossConfirmations = 0
    @Volatile private var advertisedGroupRole = AdvertisedGroupRole.NONE
    @Volatile private var desiredGroupRole = AdvertisedGroupRole.NONE
    @Volatile private var serviceRoleRetryAttempt = 0
    @Volatile private var groupFormedAtNanos: Long? = null
    @Volatile private var groupOwnerAddress: InetAddress? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var mediaServerSocket: ServerSocket? = null
    @Volatile private var mediaServerJob: Job? = null
    @Volatile private var clientJob: Job? = null
    @Volatile private var connectionTimeoutJob: Job? = null
    @Volatile private var connectRequestCallbackJob: Job? = null
    @Volatile private var serviceRetryJob: Job? = null
    @Volatile private var reconciliationJob: Job? = null
    @Volatile private var connectionInfoWatchdogJob: Job? = null
    @Volatile private var groupVerificationWatchdogJob: Job? = null
    @Volatile private var groupLossRecheckJob: Job? = null
    @Volatile private var discoveryRetryJob: Job? = null
    @Volatile private var serviceSetupWatchdogJob: Job? = null
    @Volatile private var discoveryWatchdogJob: Job? = null
    @Volatile private var serviceRoleRetryJob: Job? = null
    @Volatile private var serviceRoleWatchdogJob: Job? = null
    @Volatile private var serviceDiscoveryActive = false
    @Volatile private var discoveryRetryAttempt = 0
    @Volatile private var discoveryRequestStartedAtNanos: Long? = null
    @Volatile private var localService: WifiP2pDnsSdServiceInfo? = null
    @Volatile private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    @Volatile private var currentAttemptId: String? = null
    @Volatile private var currentPeer: String? = null
    @Volatile private var currentAttemptPeerAddress: String? = null
    @Volatile private var currentAttemptStartedAt: Long? = null
    private val groupAttemptId = AtomicReference<String?>(null)
    private val discoveredCandidates = ConcurrentHashMap<String, WifiDiscoveryCandidate>()
    private val _runtimeState = MutableStateFlow(WifiDirectRuntimeState())
    val runtimeState: StateFlow<WifiDirectRuntimeState> = _runtimeState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        resetRuntimeForP2pLoss(clearChannel = false)
                        publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_RADIO_DISABLED", retryCount = 0)
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
                    // A transient disconnected broadcast is not authoritative for
                    // group teardown. Reconcile connection and group info first so
                    // one stale snapshot cannot close healthy peer sessions.
                    requestConnectionInfo()
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> Unit
                AndroidLocationManager.MODE_CHANGED_ACTION -> {
                    if (started) {
                        if (isLocationModeEnabled()) {
                            discoveryRetryJob?.cancel(); discoveryRetryJob = null
                            discoveryRetryAttempt = 0
                            if (localService == null || serviceRequest == null) installServiceAndDiscover()
                            else beginDiscovery()
                        } else if (!groupFormed) {
                            synchronized(discoveryLock) {
                                discoveryRequestGeneration.incrementAndGet()
                                discoveryRequestInProgress.set(false)
                                serviceDiscoveryActive = false
                                discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
                            }
                            discoveryRetryJob?.cancel(); discoveryRetryJob = null
                            publishRuntimeState(WifiDirectStage.DEGRADED, "LOCATION_MODE_OFF")
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (!transportPreferences.selection.value.wifiDirectEnabled) {
            stop()
            publishRuntimeState(WifiDirectStage.STOPPED, error = null)
            return false
        }
        if (started) {
            if (hasRequiredPermissions() && channel != null) return true
            stop()
        }
        if (manager == null) {
            publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_SERVICE_UNAVAILABLE")
            return false
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_UNSUPPORTED")
            return false
        }
        if (!hasRequiredPermissions()) {
            publishRuntimeState(WifiDirectStage.DEGRADED, "PERMISSION_REQUIRED")
            return false
        }
        val identity = identityStore.getIdentity()
        if (identity.nodeId.isBlank() || identity.deviceName.isBlank() || !identityStore.isNicknameConfigured()) {
            publishRuntimeState(WifiDirectStage.DEGRADED, "IDENTITY_NOT_READY")
            return false
        }

        started = true
        publishRuntimeState(WifiDirectStage.STARTING)
        channel = createChannel()
        if (channel == null) {
            started = false
            publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_CHANNEL_UNAVAILABLE")
            return false
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(AndroidLocationManager.MODE_CHANGED_ACTION)
        }
        try {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (failure: Exception) {
            started = false
            channel = null
            publishRuntimeState(WifiDirectStage.DEGRADED, "P2P_RECEIVER_REGISTRATION_${failure.javaClass.simpleName}")
            return false
        }
        installServiceAndDiscover()
        requestConnectionInfo()
        reconciliationJob = scope.launch {
            while (isActive && started) {
                requestConnectionInfo()
                pruneExpiredCandidates()
                delay(CONNECTION_RECONCILE_INTERVAL_MS)
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!started && !receiverRegistered) {
            publishRuntimeState(WifiDirectStage.STOPPED, error = null, retryCount = 0)
            return
        }
        started = false
        groupSessionGeneration.advance()
        healthProbeJob.getAndSet(null)?.cancel()
        resolveGroupAttemptAsStopped("MANAGER_STOPPED")
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        groupClientCount = 0
        groupClientAddresses.clear()
        groupLossConfirmations = 0
        groupFormedAtNanos = null
        groupVerificationGeneration.incrementAndGet()
        groupVerificationInProgress.set(false)
        groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
        groupLossRecheckJob?.cancel(); groupLossRecheckJob = null
        desiredGroupRole = AdvertisedGroupRole.NONE
        advertisedGroupRole = AdvertisedGroupRole.NONE
        serviceRoleGeneration.incrementAndGet()
        serviceRoleRetryJob?.cancel(); serviceRoleRetryJob = null
        serviceRoleWatchdogJob?.cancel(); serviceRoleWatchdogJob = null
        serviceRoleUpdateInProgress.set(false)
        serviceSetupGeneration.incrementAndGet()
        serviceSetupInProgress.set(false)
        connectionAttemptGeneration.incrementAndGet()
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        serviceDiscoveryActive = false
        discoveryRetryAttempt = 0
        discoveryRequestInProgress.set(false)
        connectionInfoRequestInProgress.set(false)
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
        serviceSetupWatchdogJob?.cancel(); serviceSetupWatchdogJob = null
        discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        discoveryRequestGeneration.incrementAndGet()
        serviceRetryJob?.cancel(); serviceRetryJob = null
        reconciliationJob?.cancel(); reconciliationJob = null
        candidateRetryJobs.values.forEach { it.cancel() }
        candidateRetryJobs.clear()
        candidateFailureCounts.clear()
        candidateRetryAfter.clear()
        connectionStarts.clear()
        clientJob?.cancel(); clientJob = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        closeAllSessions("MANAGER_STOPPED")
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
        servicePeerAdvertisements.clear()
        compatibleServiceAddresses.clear()
        discoveredCandidates.clear()
        connectAttempts.clear()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        channel = null
        publishRuntimeState(WifiDirectStage.STOPPED, retryCount = 0)
    }

    fun isStarted(): Boolean = transportPreferences.selection.value.wifiDirectEnabled &&
        started && channel != null && hasRequiredPermissions()
    fun isDiscoveryActive(): Boolean =
        isStarted() && serviceRequest != null && serviceDiscoveryActive
    fun isAvailable(): Boolean = transportPreferences.selection.value.wifiDirectEnabled &&
        started && sessions.values.any { it.probeFailurePolicy.isHealthy }
    fun connectedPeerCount(): Int = sessions.values.count { it.probeFailurePolicy.isHealthy }
    fun hasPeer(nodeId: String): Boolean = transportPreferences.selection.value.wifiDirectEnabled &&
        started && sessions[normalize(nodeId)]?.probeFailurePolicy?.isHealthy == true

    private fun peerRuntimeSnapshot(): List<WifiDirectPeerRuntimeState> {
        val now = System.currentTimeMillis()
        val activeKeys = HashSet<String>()
        val activePeers = sessions.values.map { session ->
            val key = normalize(session.peer.nodeId)
            activeKeys.add(key)
            val healthy = session.probeFailurePolicy.isHealthy
            val prior = peerRuntimeHistory[key]
            WifiDirectPeerRuntimeState(
                nodeId = session.peer.nodeId,
                deviceName = session.peer.deviceName,
                groupRole = if (groupOwner) "GROUP_CLIENT" else "GROUP_OWNER",
                connectionStage = if (healthy) "SESSION_READY" else "SESSION_UNHEALTHY",
                tcpSessionHealthy = healthy,
                routeEligible = healthy && started && transportPreferences.selection.value.wifiDirectEnabled,
                attemptInProgress = false,
                latestAttemptDurationMs = prior?.latestAttemptDurationMs,
                lastFailureReason = if (healthy) null else prior?.lastFailureReason ?: "HEALTH_PROBE_FAILED",
                updatedAt = prior?.updatedAt ?: now
            )
        }
        val recentDisconnectedPeers = peerRuntimeHistory.entries
            .filter { (key, peer) -> key !in activeKeys && now - peer.updatedAt <= PEER_RUNTIME_RETENTION_MS }
            .map { it.value }
        return (activePeers + recentDisconnectedPeers)
            .sortedBy { it.nodeId }
            .take(MAX_WIFI_PEER_DIAGNOSTICS)
    }

    private fun recordPeerSessionReady(peer: WifiPeerSessionInfo, durationMs: Long?) {
        val key = normalize(peer.nodeId)
        peerRuntimeHistory[key] = WifiDirectPeerRuntimeState(
            nodeId = peer.nodeId,
            deviceName = peer.deviceName,
            groupRole = if (groupOwner) "GROUP_CLIENT" else "GROUP_OWNER",
            connectionStage = "SESSION_READY",
            tcpSessionHealthy = true,
            routeEligible = started && transportPreferences.selection.value.wifiDirectEnabled,
            attemptInProgress = false,
            latestAttemptDurationMs = durationMs,
            lastFailureReason = null
        )
        trimPeerRuntimeHistory()
    }

    private fun recordPeerHealth(session: PeerSession, durationMs: Long) {
        val key = normalize(session.peer.nodeId)
        val healthy = session.probeFailurePolicy.isHealthy
        val prior = peerRuntimeHistory[key]
        peerRuntimeHistory[key] = WifiDirectPeerRuntimeState(
            nodeId = session.peer.nodeId,
            deviceName = session.peer.deviceName,
            groupRole = if (groupOwner) "GROUP_CLIENT" else "GROUP_OWNER",
            connectionStage = if (healthy) "SESSION_READY" else "SESSION_UNHEALTHY",
            tcpSessionHealthy = healthy,
            routeEligible = healthy && started && transportPreferences.selection.value.wifiDirectEnabled,
            attemptInProgress = false,
            latestAttemptDurationMs = prior?.latestAttemptDurationMs,
            lastFailureReason = if (healthy) null else "HEALTH_PROBE_FAILED",
            updatedAt = System.currentTimeMillis()
        )
        trimPeerRuntimeHistory()
    }

    private fun recordPeerSessionClosed(peer: WifiPeerSessionInfo, reason: String) {
        val key = normalize(peer.nodeId)
        val prior = peerRuntimeHistory[key]
        peerRuntimeHistory[key] = WifiDirectPeerRuntimeState(
            nodeId = peer.nodeId,
            deviceName = peer.deviceName,
            groupRole = prior?.groupRole ?: if (groupOwner) "GROUP_CLIENT" else "GROUP_OWNER",
            connectionStage = "DISCONNECTED",
            tcpSessionHealthy = false,
            routeEligible = false,
            attemptInProgress = false,
            latestAttemptDurationMs = prior?.latestAttemptDurationMs,
            lastFailureReason = reason
        )
        trimPeerRuntimeHistory()
    }

    private fun runtimeStageAfterPeerChange(): WifiDirectStage = when {
        sessions.values.any { it.probeFailurePolicy.isHealthy } -> WifiDirectStage.SESSION_READY
        groupFormed && groupOwner && serverSocket != null -> WifiDirectStage.CONTROL_SERVER_READY
        groupFormed && !groupOwner -> WifiDirectStage.CONNECTING_TO_GROUP_OWNER
        groupFormed -> WifiDirectStage.GROUP_FORMED
        else -> WifiDirectStage.DEGRADED
    }

    private fun trimPeerRuntimeHistory() {
        val now = System.currentTimeMillis()
        peerRuntimeHistory.entries.removeIf { now - it.value.updatedAt > PEER_RUNTIME_RETENTION_MS }
        val excess = peerRuntimeHistory.size - MAX_WIFI_PEER_DIAGNOSTICS
        if (excess > 0) {
            peerRuntimeHistory.entries
                .sortedBy { it.value.updatedAt }
                .take(excess)
                .forEach { peerRuntimeHistory.remove(it.key, it.value) }
        }
    }

    private fun publishRuntimeState(
        stage: WifiDirectStage,
        error: String? = null,
        retryCount: Int = _runtimeState.value.retryCount
    ) {
        val previous = _runtimeState.value
        val now = System.currentTimeMillis()
        _runtimeState.value = previous.copy(
            stage = stage,
            groupOwner = groupOwner,
            groupFormed = groupFormed,
            groupOwnerAddress = groupOwnerAddress?.hostAddress,
            controlServerReady = serverSocket != null,
            mediaServerReady = mediaServerSocket != null,
            sessionCount = sessions.size,
            healthySessionCount = sessions.values.count { it.probeFailurePolicy.isHealthy },
            peers = peerRuntimeSnapshot(),
            groupClientCount = groupClientCount,
            candidates = discoveredCandidates.values.sortedByDescending { it.lastSeen },
            currentAttemptId = currentAttemptId,
            currentPeer = currentPeer,
            locationModeEnabled = isLocationModeEnabled(),
            retryCount = retryCount,
            lastError = error,
            stageSince = if (previous.stage == stage) previous.stageSince else now,
            updatedAt = now
        )
        if (previous.stage != stage || previous.lastError != error) {
            diagnostics.record(
                kind = if (error == null) NetworkEventKind.CONNECTION_STAGE else NetworkEventKind.REQUEST_FAILED,
                operation = "wifi.p2p.${stage.name.lowercase()}",
                correlationId = currentAttemptId,
                transport = "WIFI_DIRECT",
                result = error ?: "OK"
            )
        }
    }

    suspend fun sendToPeer(nodeId: String, payload: ByteArray): Boolean {
        if (!transportPreferences.selection.value.wifiDirectEnabled || !started) return false
        if (payload.isEmpty() || payload.size > MeshPacketCodec.MAX_PACKET_BYTES) return false
        val session = sessions[normalize(nodeId)] ?: return false
        if (!session.probeFailurePolicy.isHealthy) return false
        return session.send(payload, transferIds.incrementAndGet())
    }

    /** Sends a file on a separate bounded TCP port; the mesh packet codec is untouched. */
    suspend fun sendAttachmentToPeer(
        nodeId: String,
        manifest: AttachmentTransferManifest,
        file: File,
        onProgress: suspend (Long) -> Unit = {}
    ): Boolean {
        if (!transportPreferences.selection.value.wifiDirectEnabled || !started) return false
        if (!manifest.isValid() || !file.isFile || file.length() != manifest.sizeBytes) return false
        val session = sessions[normalize(nodeId)] ?: return false
        if (!session.probeFailurePolicy.isHealthy) return false
        val peer = session.peer
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
                onProgress(offset)

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

    private fun createLocalServiceInfo(groupRole: AdvertisedGroupRole): WifiP2pDnsSdServiceInfo? =
        runCatching {
            WifiP2pDnsSdServiceInfo.newInstance(
                "neyra",
                "_neyra._tcp",
                mapOf(
                    "v" to MeshPacketCodec.PROTOCOL_VERSION.toString(),
                    // Ungrouped peers use a per-process tie-breaker. A formed
                    // owner advertises availability; clients advertise that they
                    // are already grouped so a new phone waits for the owner.
                    "r" to discoveryToken,
                    "g" to if (groupRole == AdvertisedGroupRole.OWNER) "1" else "0",
                    "p" to if (groupRole == AdvertisedGroupRole.NONE) "0" else "1"
                )
            )
        }.getOrNull()

    private fun localAdvertisedGroupRole(): AdvertisedGroupRole = when {
        !groupFormed -> AdvertisedGroupRole.NONE
        groupOwner -> AdvertisedGroupRole.OWNER
        else -> AdvertisedGroupRole.CLIENT
    }

    @SuppressLint("MissingPermission")
    private fun updateLocalServiceGroupRole(groupRole: AdvertisedGroupRole) {
        desiredGroupRole = groupRole
        if (!started || !hasRequiredPermissions() || serviceSetupInProgress.get()) return
        if (localService == null && serviceRequest == null) return
        if (localService != null && advertisedGroupRole == groupRole) {
            if (!groupFormed) beginDiscovery()
            return
        }
        if (!serviceRoleUpdateInProgress.compareAndSet(false, true)) return

        val current = channel
        val p2pManager = manager
        val replacement = createLocalServiceInfo(groupRole)
        if (current == null || p2pManager == null || replacement == null) {
            serviceRoleUpdateInProgress.set(false)
            scheduleServiceRoleRetry()
            return
        }
        val generation = serviceRoleGeneration.incrementAndGet()
        val previous = localService
        serviceRoleWatchdogJob?.cancel()
        serviceRoleWatchdogJob = scope.launch {
            delay(SERVICE_CALLBACK_TIMEOUT_MS)
            if (serviceRoleGeneration.get() == generation &&
                serviceRoleUpdateInProgress.compareAndSet(true, false)) {
                serviceRoleWatchdogJob = null
                serviceRoleGeneration.compareAndSet(generation, generation + 1L)
                previous?.let { runCatching { manager?.removeLocalService(current, it, null) } }
                localService = null
                diagnostics.record(
                    kind = NetworkEventKind.REQUEST_FAILED,
                    operation = "wifi.discovery.group_owner_advertisement_timeout",
                    correlationId = currentAttemptId,
                    transport = "WIFI_DIRECT",
                    result = "SERVICE_ROLE_CALLBACK_TIMEOUT"
                )
                scheduleServiceRoleRetry()
            }
        }

        fun finish(success: Boolean, failure: String? = null) {
            if (serviceRoleGeneration.get() != generation || channel !== current || !started) {
                return
            }
            serviceRoleWatchdogJob?.cancel(); serviceRoleWatchdogJob = null
            if (success) {
                localService = replacement
                advertisedGroupRole = groupRole
                serviceRoleRetryAttempt = 0
                serviceRoleRetryJob?.cancel(); serviceRoleRetryJob = null
                diagnostics.record(
                    kind = NetworkEventKind.CONNECTION_STAGE,
                    operation = "wifi.discovery.group_role_advertisement",
                    correlationId = currentAttemptId,
                    transport = "WIFI_DIRECT",
                    durationMs = groupFormedAtNanos?.let {
                        ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                    },
                    result = when (groupRole) {
                        AdvertisedGroupRole.NONE -> "UNGROUPED"
                        AdvertisedGroupRole.OWNER -> "GROUP_OWNER_AVAILABLE"
                        AdvertisedGroupRole.CLIENT -> "GROUP_CLIENT"
                    }
                )
            } else {
                diagnostics.record(
                    kind = NetworkEventKind.REQUEST_FAILED,
                    operation = "wifi.discovery.group_role_advertisement_failed",
                    correlationId = currentAttemptId,
                    transport = "WIFI_DIRECT",
                    result = failure ?: "UNKNOWN"
                )
            }
            serviceRoleUpdateInProgress.set(false)
            if (success && desiredGroupRole != advertisedGroupRole) {
                updateLocalServiceGroupRole(desiredGroupRole)
            } else if (!success) {
                scheduleServiceRoleRetry()
            } else if (!groupFormed) {
                beginDiscovery()
            }
        }

        fun addReplacement() {
            try {
                p2pManager.addLocalService(current, replacement, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = finish(true)
                    override fun onFailure(reason: Int) {
                        if (previous != null) localService = null
                        finish(false, "ADD_LOCAL_SERVICE_FAILED_$reason")
                    }
                })
            } catch (failure: Exception) {
                if (previous != null) localService = null
                finish(false, "ADD_LOCAL_SERVICE_EXCEPTION_${failure.javaClass.simpleName}")
            }
        }

        if (previous == null) {
            addReplacement()
        } else {
            try {
                p2pManager.removeLocalService(current, previous, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (serviceRoleGeneration.get() == generation && channel === current && started) {
                            localService = null
                            addReplacement()
                        }
                    }

                    override fun onFailure(reason: Int) = finish(false, "REMOVE_LOCAL_SERVICE_FAILED_$reason")
                })
            } catch (failure: Exception) {
                finish(false, "REMOVE_LOCAL_SERVICE_EXCEPTION_${failure.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleServiceRoleRetry() {
        if (!started || serviceRoleRetryJob?.isActive == true) return
        val shift = serviceRoleRetryAttempt.coerceAtMost(4)
        val delayMs = (SERVICE_SETUP_RETRY_MS * (1L shl shift)).coerceAtMost(DISCOVERY_RETRY_MAX_MS)
        serviceRoleRetryAttempt = (serviceRoleRetryAttempt + 1).coerceAtMost(5)
        serviceRoleRetryJob = scope.launch {
            delay(delayMs)
            serviceRoleRetryJob = null
            if (started) updateLocalServiceGroupRole(desiredGroupRole)
        }
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
        val service = createLocalServiceInfo(AdvertisedGroupRole.NONE) ?: run {
            serviceSetupInProgress.set(false)
            scheduleServiceRetry()
            return
        }
        localService = service
        advertisedGroupRole = AdvertisedGroupRole.NONE
        desiredGroupRole = AdvertisedGroupRole.NONE
        val p2pManager = manager
        if (p2pManager == null) {
            localService = null
            serviceSetupInProgress.set(false)
            return
        }
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        serviceRequest = request
        var localServiceReady = false
        var serviceRequestReady = false
        fun completeRegistrationIfReady() = synchronized(serviceSetupLock) {
            if (!isCurrentSetup(current, generation) || !serviceSetupInProgress.get()) return@synchronized
            if (!localServiceReady || !serviceRequestReady) return@synchronized
            serviceSetupWatchdogJob?.cancel(); serviceSetupWatchdogJob = null
            serviceSetupInProgress.set(false)
            publishRuntimeState(WifiDirectStage.DISCOVERING)
            if (groupFormed) updateLocalServiceGroupRole(localAdvertisedGroupRole())
            else beginDiscovery()
        }

        try {
            // Install callbacks before registering the request. Submit local
            // advertisement and remote discovery registration in the same setup
            // turn: neither operation depends on the other's completion callback.
            p2pManager.setDnsSdResponseListeners(
                current,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
                    if (isCurrentSetup(current, generation) && instanceName.equals("neyra", true) &&
                        device != null && !groupFormed) {
                        val key = device.deviceAddress.lowercase()
                        rememberCandidate(device, decision = "NEYRA_SERVICE_SEEN")
                        if (advertisedServiceDevices.size >= MAX_DISCOVERED_SERVICE_DEVICES) {
                            advertisedServiceDevices.clear()
                            servicePeerAdvertisements.clear()
                            compatibleServiceAddresses.clear()
                        }
                        val firstServiceResponse = advertisedServiceDevices.put(key, device) == null
                        if (firstServiceResponse) {
                            diagnostics.record(
                                kind = NetworkEventKind.DISCOVERY_CANDIDATE,
                                operation = "wifi.discovery.service_found",
                                correlationId = currentAttemptId,
                                transport = "WIFI_DIRECT",
                                durationMs = discoveryRequestStartedAtNanos?.let {
                                    ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                                },
                                result = device.deviceName?.takeIf { it.isNotBlank() } ?: "Wi-Fi Direct peer"
                            )
                        }
                        if (key in compatibleServiceAddresses) {
                            val advertisement = servicePeerAdvertisements[key]
                            if (advertisement != null) connectToService(device, advertisement)
                        }
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    // TXT carries protocol metadata only; authoritative Neyra identity
                    // is exchanged over the socket HELLO after the P2P group forms.
                    if (isCurrentSetup(current, generation) && device != null && !groupFormed) {
                        val key = device.deviceAddress.lowercase()
                        val remoteVersion = record.orEmpty()["v"]
                        if (remoteVersion == MeshPacketCodec.PROTOCOL_VERSION.toString()) {
                            if (compatibleServiceAddresses.size >= MAX_DISCOVERED_SERVICE_DEVICES) {
                                compatibleServiceAddresses.clear()
                                advertisedServiceDevices.clear()
                                servicePeerAdvertisements.clear()
                            }
                            val advertisement = WifiDirectPeerAdvertisement(
                                protocolVersion = remoteVersion,
                                discoveryToken = record.orEmpty()["r"]?.takeIf { it.isNotBlank() },
                                groupOwnerAvailable = WifiDirectAdmissionPolicy.advertisesGroupOwner(record),
                                groupFormed = WifiDirectAdmissionPolicy.advertisesFormedGroup(record)
                            )
                            servicePeerAdvertisements[key] = advertisement
                            val action = candidateAction(key, advertisement)
                            val decision = when (action) {
                                WifiDirectAdmissionPolicy.CandidateAction.CONNECT ->
                                    if (advertisement.groupOwnerAvailable) "JOIN_ADVERTISED_GROUP_OWNER" else "COMPATIBLE_INITIATOR"
                                WifiDirectAdmissionPolicy.CandidateAction.INCOMPATIBLE -> "INCOMPATIBLE_PROTOCOL"
                                WifiDirectAdmissionPolicy.CandidateAction.LOCAL_GROUP_ALREADY_FORMED -> "LOCAL_GROUP_ALREADY_FORMED"
                                WifiDirectAdmissionPolicy.CandidateAction.ALREADY_CONNECTED -> "ALREADY_A_GROUP_MEMBER"
                                WifiDirectAdmissionPolicy.CandidateAction.BUSY -> "WAITING_FOR_ACTIVE_ATTEMPT"
                                WifiDirectAdmissionPolicy.CandidateAction.BACKOFF -> "CANDIDATE_RETRY_BACKOFF"
                                WifiDirectAdmissionPolicy.CandidateAction.WAIT_FOR_GROUP_OWNER -> "WAITING_FOR_GROUP_OWNER"
                                WifiDirectAdmissionPolicy.CandidateAction.WAIT_FOR_LEGACY_PEER ->
                                    if (advertisement.discoveryToken == null) "COMPATIBLE_WAITING_FOR_LEGACY_PEER"
                                    else "COMPATIBLE_WAITING_FOR_PEER"
                            }
                            rememberCandidate(device, protocolVersion = remoteVersion, decision = decision)
                            if (action == WifiDirectAdmissionPolicy.CandidateAction.CONNECT) {
                                compatibleServiceAddresses.add(key)
                                advertisedServiceDevices[key]?.let {
                                    if (started && !groupFormed) connectToService(it, advertisement)
                                }
                            } else {
                                compatibleServiceAddresses.remove(key)
                            }
                        } else {
                            rememberCandidate(device, protocolVersion = remoteVersion, decision = "INCOMPATIBLE_PROTOCOL")
                            compatibleServiceAddresses.remove(key)
                            servicePeerAdvertisements.remove(key)
                        }
                    }
                }
            )
        } catch (failure: Exception) {
            failServiceSetup(current, generation, "SERVICE_LISTENER_SETUP_EXCEPTION_${failure.javaClass.simpleName}")
            return
        }

        armServiceSetupWatchdog(current, generation, "REGISTER_SERVICES")
        try {
            p2pManager.addLocalService(current, service, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = synchronized(serviceSetupLock) {
                    if (!isCurrentSetup(current, generation) || !serviceSetupInProgress.get()) {
                        runCatching { manager?.removeLocalService(current, service, null) }
                        return@synchronized
                    }
                    localServiceReady = true
                    completeRegistrationIfReady()
                }

                override fun onFailure(reason: Int) {
                    failServiceSetup(current, generation, "ADD_LOCAL_SERVICE_FAILED_$reason")
                }
            })
            p2pManager.addServiceRequest(current, request, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = synchronized(serviceSetupLock) {
                    if (!isCurrentSetup(current, generation) || !serviceSetupInProgress.get()) return@synchronized
                    serviceRequestReady = true
                    completeRegistrationIfReady()
                }

                override fun onFailure(reason: Int) {
                    failServiceSetup(current, generation, "ADD_SERVICE_REQUEST_FAILED_$reason")
                }
            })
        } catch (failure: Exception) {
            failServiceSetup(current, generation, "SERVICE_SETUP_EXCEPTION_${failure.javaClass.simpleName}")
        }
    }

    private fun isCurrentSetup(candidate: WifiP2pManager.Channel, generation: Long): Boolean =
        started && channel === candidate && serviceSetupGeneration.get() == generation

    private fun armServiceSetupWatchdog(
        current: WifiP2pManager.Channel,
        generation: Long,
        phase: String
    ) {
        serviceSetupWatchdogJob?.cancel()
        serviceSetupWatchdogJob = scope.launch {
            delay(SERVICE_CALLBACK_TIMEOUT_MS)
            synchronized(serviceSetupLock) {
                if (isCurrentSetup(current, generation) && serviceSetupInProgress.compareAndSet(true, false)) {
                    // Invalidate every callback belonging to this timed-out attempt
                    // before the retry can install a replacement registration.
                    serviceSetupGeneration.compareAndSet(generation, generation + 1L)
                    runCatching { manager?.clearServiceRequests(current, null) }
                    localService?.let { runCatching { manager?.removeLocalService(current, it, null) } }
                    localService = null
                    serviceRequest = null
                    serviceDiscoveryActive = false
                    publishRuntimeState(WifiDirectStage.DEGRADED, "${phase}_CALLBACK_TIMEOUT")
                    scheduleServiceRetry()
                }
            }
        }
    }

    private fun failServiceSetup(
        current: WifiP2pManager.Channel,
        generation: Long,
        error: String
    ) = synchronized(serviceSetupLock) {
        if (!isCurrentSetup(current, generation) || !serviceSetupInProgress.get()) return@synchronized
        serviceSetupInProgress.set(false)
        serviceSetupGeneration.compareAndSet(generation, generation + 1L)
        serviceSetupWatchdogJob?.cancel(); serviceSetupWatchdogJob = null
        runCatching { manager?.clearServiceRequests(current, null) }
        localService?.let { runCatching { manager?.removeLocalService(current, it, null) } }
        localService = null
        serviceRequest = null
        serviceDiscoveryActive = false
        publishRuntimeState(WifiDirectStage.DEGRADED, error)
        scheduleServiceRetry()
    }

    private fun rememberCandidate(
        device: WifiP2pDevice,
        protocolVersion: String? = null,
        decision: String
    ) {
        val address = device.deviceAddress?.lowercase().orEmpty()
        if (address.isBlank()) return
        val now = System.currentTimeMillis()
        pruneExpiredCandidates(now)
        val candidate = WifiDiscoveryCandidate(
            deviceName = device.deviceName?.takeIf { it.isNotBlank() } ?: "Wi-Fi Direct peer",
            deviceAddress = address,
            protocolVersion = protocolVersion,
            decision = decision,
            lastSeen = now
        )
        discoveredCandidates[address] = candidate
        diagnostics.record(
            kind = NetworkEventKind.DISCOVERY_CANDIDATE,
            operation = "wifi.discovery.candidate",
            correlationId = currentAttemptId,
            transport = "WIFI_DIRECT",
            result = "${candidate.deviceName}; version=${protocolVersion ?: "pending"}; $decision",
            timestamp = now
        )
        publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError)
    }

    private fun candidateAction(
        address: String,
        advertisement: WifiDirectPeerAdvertisement,
        nowMs: Long = System.currentTimeMillis()
    ): WifiDirectAdmissionPolicy.CandidateAction = WifiDirectAdmissionPolicy.candidateAction(
        started = started && transportPreferences.selection.value.wifiDirectEnabled,
        protocolCompatible = advertisement.protocolVersion == MeshPacketCodec.PROTOCOL_VERSION.toString(),
        localGroupFormed = groupFormed,
        remoteAdvertisesGroupOwner = advertisement.groupOwnerAvailable,
        remoteGroupFormed = advertisement.groupFormed,
        localToken = discoveryToken,
        remoteToken = advertisement.discoveryToken,
        alreadyMember = address in groupClientAddresses,
        sessionAlreadyEstablished = address in groupClientAddresses,
        anotherAttemptInProgress = connectionAttemptInProgress.get(),
        retryAfterMs = candidateRetryAfter[address] ?: 0L,
        nowMs = nowMs
    )

    private fun recordCandidateFailure(address: String, reason: String) {
        val key = address.lowercase()
        val failures = candidateFailureCounts.compute(key) { _, previous -> (previous ?: 0) + 1 } ?: 1
        val delayMs = WifiDirectAdmissionPolicy.retryDelayMs(failures)
        val retryAt = System.currentTimeMillis() + delayMs
        candidateRetryAfter[key] = retryAt
        discoveredCandidates.computeIfPresent(key) { _, candidate ->
            candidate.copy(decision = "RETRY_${failures}_IN_${delayMs}MS", attemptInProgress = false)
        }
        diagnostics.record(
            kind = NetworkEventKind.REQUEST_FAILED,
            operation = "wifi.p2p.candidate_retry_scheduled",
            correlationId = currentAttemptId,
            transport = "WIFI_DIRECT",
            attempt = failures,
            result = "$reason; retryInMs=$delayMs"
        )
        publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError)

        candidateRetryJobs.remove(key)?.cancel()
        val retryJob = scope.launch {
            delay(delayMs)
            candidateRetryJobs.remove(key)
            if (!started || groupFormed) return@launch
            val device = advertisedServiceDevices[key] ?: return@launch
            val advertisement = servicePeerAdvertisements[key] ?: return@launch
            val candidate = discoveredCandidates[key] ?: return@launch
            if (System.currentTimeMillis() - candidate.lastSeen > CANDIDATE_RETENTION_MS) return@launch
            if (candidateAction(key, advertisement) == WifiDirectAdmissionPolicy.CandidateAction.CONNECT) {
                connectToService(device, advertisement)
            }
        }
        candidateRetryJobs[key] = retryJob
    }

    private fun clearCandidateFailure(address: String?, decision: String) {
        val key = address?.lowercase() ?: return
        candidateFailureCounts.remove(key)
        candidateRetryAfter.remove(key)
        candidateRetryJobs.remove(key)?.cancel()
        discoveredCandidates.computeIfPresent(key) {
                _, candidate -> candidate.copy(decision = decision, attemptInProgress = false)
        }
        publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError)
    }

    private fun pruneExpiredCandidates(now: Long = System.currentTimeMillis()) {
        val removed = discoveredCandidates.entries.removeIf {
            now - it.value.lastSeen > CANDIDATE_RETENTION_MS
        }
        if (removed) {
            val liveAddresses = discoveredCandidates.keys
            candidateRetryJobs.keys.toList().filterNot { it in liveAddresses }.forEach { key ->
                candidateRetryJobs.remove(key)?.cancel()
                candidateFailureCounts.remove(key)
                candidateRetryAfter.remove(key)
                advertisedServiceDevices.remove(key)
                servicePeerAdvertisements.remove(key)
                compatibleServiceAddresses.remove(key)
            }
        }
        if (removed) {
            publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError)
        }
    }

    private fun isLocationModeEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.getSystemService(AndroidLocationManager::class.java)?.isLocationEnabled == true
        } else {
            Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    }.getOrDefault(false)

    private fun resetServiceRegistrationState() {
        serviceSetupGeneration.incrementAndGet()
        serviceSetupInProgress.set(false)
        synchronized(discoveryLock) {
            discoveryRequestGeneration.incrementAndGet()
            discoveryRequestInProgress.set(false)
            discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        }
        serviceRetryJob?.cancel(); serviceRetryJob = null
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        discoveryRequestInProgress.set(false)
        serviceDiscoveryActive = false
        discoveryRetryAttempt = 0
        localService = null
        advertisedGroupRole = AdvertisedGroupRole.NONE
        desiredGroupRole = AdvertisedGroupRole.NONE
        serviceRoleGeneration.incrementAndGet()
        serviceRoleUpdateInProgress.set(false)
        serviceRoleRetryJob?.cancel(); serviceRoleRetryJob = null
        serviceRoleWatchdogJob?.cancel(); serviceRoleWatchdogJob = null
        serviceRequest = null
        advertisedServiceDevices.clear()
        servicePeerAdvertisements.clear()
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
        groupSessionGeneration.advance()
        resolveGroupAttemptAsStopped("P2P_UNAVAILABLE")
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        groupClientCount = 0
        groupClientAddresses.clear()
        groupLossConfirmations = 0
        groupFormedAtNanos = null
        groupVerificationGeneration.incrementAndGet()
        groupVerificationInProgress.set(false)
        groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
        desiredGroupRole = AdvertisedGroupRole.NONE
        advertisedGroupRole = AdvertisedGroupRole.NONE
        serviceRoleGeneration.incrementAndGet()
        serviceRoleUpdateInProgress.set(false)
        serviceRoleRetryJob?.cancel(); serviceRoleRetryJob = null
        serviceRoleWatchdogJob?.cancel(); serviceRoleWatchdogJob = null
        discoveryRetryAttempt = 0
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        synchronized(discoveryLock) {
            discoveryRequestGeneration.incrementAndGet()
            discoveryRequestInProgress.set(false)
            discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        }
        serviceDiscoveryActive = false
        connectionInfoRequestInProgress.set(false)
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionAttemptGeneration.incrementAndGet()
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
        clientJob?.cancel(); clientJob = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        closeAllSessions("P2P_UNAVAILABLE")
        connectAttempts.clear()
        candidateRetryJobs.values.forEach { it.cancel() }
        candidateRetryJobs.clear()
        candidateFailureCounts.clear()
        candidateRetryAfter.clear()
        connectionStarts.clear()
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
            servicePeerAdvertisements.clear()
            compatibleServiceAddresses.clear()
        }
        publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError, retryCount = 0)
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
        if (!WifiDirectAdmissionPolicy.discoveryAllowed(
                started,
                groupFormed,
                connectionAttemptInProgress.get()
            ) || !hasRequiredPermissions() ||
            localService == null || serviceRequest == null) return
        val requestGeneration = synchronized(discoveryLock) {
            if (!WifiDirectAdmissionPolicy.discoveryAllowed(
                    started,
                    groupFormed,
                    connectionAttemptInProgress.get()
                ) || !hasRequiredPermissions() ||
                localService == null || serviceRequest == null || !isLocationModeEnabled() || serviceDiscoveryActive ||
                !discoveryRequestInProgress.compareAndSet(false, true)) {
                null
            } else {
                discoveryRequestGeneration.incrementAndGet()
            }
        }
        if (requestGeneration == null) {
            if (!isLocationModeEnabled()) {
                publishRuntimeState(WifiDirectStage.DEGRADED, "LOCATION_MODE_OFF")
                scheduleDiscoveryRetry()
            }
            return
        }
        val current = channel
        val p2pManager = manager
        if (current == null || p2pManager == null) {
            synchronized(discoveryLock) {
                if (discoveryRequestGeneration.get() == requestGeneration) {
                    discoveryRequestInProgress.set(false)
                    discoveryRequestGeneration.incrementAndGet()
                }
            }
            scheduleDiscoveryRetry()
            return
        }

        discoveryRequestStartedAtNanos = System.nanoTime()
        diagnostics.record(
            kind = NetworkEventKind.CONNECTION_STAGE,
            operation = "wifi.discovery.request_started",
            correlationId = currentAttemptId,
            transport = "WIFI_DIRECT",
            result = "SERVICE_DISCOVERY_REQUESTED"
        )
        discoveryWatchdogJob?.cancel()
        discoveryWatchdogJob = scope.launch {
            delay(DISCOVERY_CALLBACK_TIMEOUT_MS)
            val timedOut = synchronized(discoveryLock) {
                if (isCurrentDiscovery(current, requestGeneration) &&
                    discoveryRequestInProgress.compareAndSet(true, false)) {
                    discoveryRequestGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
                    serviceDiscoveryActive = false
                    discoveryWatchdogJob = null
                    true
                } else false
            }
            if (timedOut) {
                publishRuntimeState(WifiDirectStage.DEGRADED, "DISCOVER_SERVICES_CALLBACK_TIMEOUT")
                scheduleDiscoveryRetry()
            }
        }

        try {
            // Android keeps service discovery active after a successful request
            // until a connection starts or a P2P group forms. Reissuing this call
            // on a timer only invites BUSY failures and does not speed up scanning.
            p2pManager.discoverServices(current, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    val accepted = synchronized(discoveryLock) {
                        if (!isCurrentDiscovery(current, requestGeneration)) return@synchronized false
                        discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
                        discoveryRequestInProgress.set(false)
                        // ActionListener callbacks are one-shot. Invalidate this
                        // token so a duplicate/late callback cannot change the state
                        // after a later attempt has started.
                        discoveryRequestGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
                        if (groupFormed) return@synchronized false
                        serviceDiscoveryActive = true
                        discoveryRetryAttempt = 0
                        discoveryRetryJob?.cancel(); discoveryRetryJob = null
                        true
                    }
                    if (accepted) {
                        diagnostics.record(
                            kind = NetworkEventKind.CONNECTION_STAGE,
                            operation = "wifi.discovery.request_ready",
                            correlationId = currentAttemptId,
                            transport = "WIFI_DIRECT",
                            durationMs = discoveryRequestStartedAtNanos?.let {
                                ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                            },
                            result = "SERVICE_DISCOVERY_ACTIVE"
                        )
                        publishRuntimeState(WifiDirectStage.DISCOVERING, retryCount = 0)
                    }
                }

                override fun onFailure(reason: Int) {
                    val accepted = synchronized(discoveryLock) {
                        if (!isCurrentDiscovery(current, requestGeneration)) return@synchronized false
                        discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
                        discoveryRequestInProgress.set(false)
                        discoveryRequestGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
                        serviceDiscoveryActive = false
                        !groupFormed
                    }
                    if (accepted) {
                        diagnostics.record(
                            kind = NetworkEventKind.REQUEST_FAILED,
                            operation = "wifi.discovery.request_failed",
                            correlationId = currentAttemptId,
                            transport = "WIFI_DIRECT",
                            durationMs = discoveryRequestStartedAtNanos?.let {
                                ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                            },
                            result = "DISCOVERY_FAILED_$reason"
                        )
                        publishRuntimeState(WifiDirectStage.DEGRADED, "DISCOVERY_FAILED_$reason")
                        scheduleDiscoveryRetry()
                    }
                }
            })
        } catch (_: Exception) {
            val accepted = synchronized(discoveryLock) {
                if (!isCurrentDiscovery(current, requestGeneration)) return@synchronized false
                discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
                discoveryRequestInProgress.set(false)
                discoveryRequestGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
                serviceDiscoveryActive = false
                !groupFormed
            }
            if (accepted) {
                publishRuntimeState(WifiDirectStage.DEGRADED, "DISCOVER_SERVICES_EXCEPTION")
                scheduleDiscoveryRetry()
            }
        }
    }

    private fun isCurrentDiscovery(current: WifiP2pManager.Channel, generation: Long): Boolean =
        started && channel === current && discoveryRequestGeneration.get() == generation

    private fun scheduleDiscoveryRetry() {
        if (!WifiDirectAdmissionPolicy.discoveryAllowed(
                started,
                groupFormed,
                connectionAttemptInProgress.get()
            ) || discoveryRetryJob?.isActive == true) return
        val delayMs = WifiDirectAdmissionPolicy.discoveryRetryDelayMs(
            retryNumber = discoveryRetryAttempt + 1,
            baseDelayMs = DISCOVERY_RETRY_BASE_MS,
            maxDelayMs = DISCOVERY_RETRY_MAX_MS
        )
        discoveryRetryAttempt = (discoveryRetryAttempt + 1).coerceAtMost(5)
        publishRuntimeState(
            _runtimeState.value.stage,
            error = _runtimeState.value.lastError,
            retryCount = discoveryRetryAttempt
        )
        discoveryRetryJob = scope.launch {
            delay(delayMs)
            discoveryRetryJob = null
            if (WifiDirectAdmissionPolicy.discoveryAllowed(
                    started,
                    groupFormed,
                    connectionAttemptInProgress.get()
                )) beginDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToService(device: WifiP2pDevice, advertisement: WifiDirectPeerAdvertisement) {
        if (!transportPreferences.selection.value.wifiDirectEnabled || !started || groupFormed || !hasRequiredPermissions()) return
        val key = device.deviceAddress?.lowercase() ?: return
        if (candidateAction(key, advertisement) != WifiDirectAdmissionPolicy.CandidateAction.CONNECT) return
        if (!connectAttempts.add(key)) return
        if (!connectionAttemptInProgress.compareAndSet(false, true)) {
            connectAttempts.remove(key)
            return
        }
        val attemptId = UUID.randomUUID().toString()
        currentAttemptId = attemptId
        val attemptGeneration = connectionAttemptGeneration.incrementAndGet()
        synchronized(discoveryLock) {
            discoveryRequestGeneration.incrementAndGet()
            discoveryRequestInProgress.set(false)
            serviceDiscoveryActive = false
            discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        }
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        currentPeer = device.deviceName?.takeIf { it.isNotBlank() } ?: key.takeLast(6)
        currentAttemptPeerAddress = key
        currentAttemptStartedAt = System.nanoTime()
        connectionStarts[attemptId] = currentAttemptStartedAt ?: System.nanoTime()
        discoveredCandidates.computeIfPresent(key) { _, candidate ->
            candidate.copy(decision = "P2P_NEGOTIATION_PENDING", attemptInProgress = true)
        }
        diagnostics.record(
            kind = NetworkEventKind.CONNECTION_STAGE,
            operation = "wifi.p2p.connect_requested",
            correlationId = attemptId,
            transport = "WIFI_DIRECT",
            result = "peer=$currentPeer; ${if (advertisement.groupOwnerAvailable) "JOIN_GROUP_OWNER" else "WPS_PBC"}"
        )
        clearCandidateFailure(key, "P2P_NEGOTIATION_PENDING")
        discoveredCandidates.computeIfPresent(key) { _, candidate -> candidate.copy(attemptInProgress = true) }
        publishRuntimeState(WifiDirectStage.NEGOTIATING)
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            // An existing group owner must remain owner when a new phone joins.
            // Token election still selects one initiator for a fresh two-phone group.
            groupOwnerIntent = if (advertisement.groupOwnerAvailable) 0
            else identityStore.getIdentity().nodeId.hashCode().ushr(1) % 16
        }
        val current = channel ?: run {
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            connectionStarts.remove(attemptId)
            recordCandidateFailure(key, "P2P_CHANNEL_UNAVAILABLE")
            return
        }
        val p2pManager = manager ?: run {
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            connectionStarts.remove(attemptId)
            recordCandidateFailure(key, "P2P_MANAGER_UNAVAILABLE")
            return
        }
        fun isCurrentAttempt(): Boolean = started && channel === current &&
            connectionAttemptGeneration.get() == attemptGeneration &&
            currentAttemptId == attemptId && connectionAttemptInProgress.get()
        connectRequestCallbackJob?.cancel()
        connectRequestCallbackJob = scope.launch {
            delay(CONNECT_REQUEST_CALLBACK_TIMEOUT_MS)
            if (!isCurrentAttempt()) return@launch
            diagnostics.record(
                kind = NetworkEventKind.REQUEST_FAILED,
                operation = "wifi.p2p.connect_callback_timeout",
                correlationId = attemptId,
                transport = "WIFI_DIRECT",
                durationMs = ((System.nanoTime() - (currentAttemptStartedAt ?: System.nanoTime())) / 1_000_000L),
                result = "Android did not invoke the connect request callback"
            )
            connectRequestCallbackJob = null
            connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            connectionAttemptGeneration.compareAndSet(attemptGeneration, attemptGeneration + 1L)
            serviceDiscoveryActive = false
            publishRuntimeState(WifiDirectStage.DEGRADED, "CONNECT_CALLBACK_TIMEOUT")
            connectionStarts.remove(attemptId)
            recordCandidateFailure(key, "CONNECT_CALLBACK_TIMEOUT")
            runCatching {
                manager?.cancelConnect(current, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = scheduleDiscoveryRetry()
                    override fun onFailure(reason: Int) = scheduleDiscoveryRetry()
                })
            }.onFailure { scheduleDiscoveryRetry() }
            scheduleDiscoveryRetry()
        }
        try {
            p2pManager.connect(current, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrentAttempt()) return
                    connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
                    serviceDiscoveryActive = false
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.connect_request_accepted",
                        correlationId = attemptId,
                        transport = "WIFI_DIRECT",
                        result = "Android accepted request initiation; group formation is pending"
                    )
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = scope.launch {
                        delay(CONNECT_NEGOTIATION_NOTICE_MS)
                        if (isCurrentAttempt()) {
                            diagnostics.record(
                                kind = NetworkEventKind.CONNECTION_STAGE,
                                operation = "wifi.p2p.negotiation_still_pending",
                                correlationId = attemptId,
                                transport = "WIFI_DIRECT",
                                durationMs = CONNECT_NEGOTIATION_NOTICE_MS,
                                result = "Waiting for Android peer/group approval"
                            )
                        }
                        delay(CONNECT_NEGOTIATION_TIMEOUT_MS - CONNECT_NEGOTIATION_NOTICE_MS)
                        if (isCurrentAttempt()) {
                            diagnostics.record(
                                kind = NetworkEventKind.REQUEST_FAILED,
                                operation = "wifi.p2p.negotiation_timeout",
                                correlationId = attemptId,
                                transport = "WIFI_DIRECT",
                                durationMs = CONNECT_NEGOTIATION_TIMEOUT_MS,
                                result = "No group formation observed before timeout"
                            )
                            connectAttempts.remove(key)
                            connectionAttemptInProgress.set(false)
                            connectionAttemptGeneration.compareAndSet(attemptGeneration, attemptGeneration + 1L)
                            publishRuntimeState(WifiDirectStage.DEGRADED, "GROUP_NEGOTIATION_TIMEOUT")
                            connectionStarts.remove(attemptId)
                            recordCandidateFailure(key, "GROUP_NEGOTIATION_TIMEOUT")
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
                    if (!isCurrentAttempt()) return
                    connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
                    connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                    connectAttempts.remove(key)
                    connectionAttemptInProgress.set(false)
                    connectionAttemptGeneration.compareAndSet(attemptGeneration, attemptGeneration + 1L)
                    serviceDiscoveryActive = false
                    publishRuntimeState(WifiDirectStage.DEGRADED, "CONNECT_FAILED_$reason")
                    connectionStarts.remove(attemptId)
                    recordCandidateFailure(key, "CONNECT_FAILED_$reason")
                    scheduleDiscoveryRetry()
                }
            })
        } catch (_: Exception) {
            if (!isCurrentAttempt()) return
            connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
            connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
            connectAttempts.remove(key)
            connectionAttemptInProgress.set(false)
            connectionAttemptGeneration.compareAndSet(attemptGeneration, attemptGeneration + 1L)
            serviceDiscoveryActive = false
            publishRuntimeState(WifiDirectStage.DEGRADED, "CONNECT_EXCEPTION")
            connectionStarts.remove(attemptId)
            recordCandidateFailure(key, "CONNECT_EXCEPTION")
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
                    val hadGroup = groupFormed
                    if (hadGroup) {
                        verifyGroupStillPresent(current)
                        return@connectionInfoCallback
                    }
                    if (hadGroup || (!serviceDiscoveryActive && !discoveryRequestInProgress.get())) {
                        beginDiscovery()
                    }
                    return@connectionInfoCallback
                }

                val sameGroup = groupFormed &&
                    groupOwner == info.isGroupOwner &&
                    groupOwnerAddress?.hostAddress == ownerAddress.hostAddress
                groupLossConfirmations = 0
                groupVerificationGeneration.incrementAndGet()
                groupVerificationInProgress.set(false)
                groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
                groupLossRecheckJob?.cancel(); groupLossRecheckJob = null
                val completedPeerAddress = currentAttemptPeerAddress
                completedPeerAddress?.let { connectAttempts.remove(it) }
                connectionAttemptInProgress.set(false)
                connectionAttemptGeneration.incrementAndGet()
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
                synchronized(discoveryLock) {
                    discoveryRequestGeneration.incrementAndGet()
                    discoveryRequestInProgress.set(false)
                    serviceDiscoveryActive = false
                    discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
                }
                discoveryRetryJob?.cancel(); discoveryRetryJob = null
                if (!sameGroup) groupSessionGeneration.advance()
                groupFormed = true
                groupOwner = info.isGroupOwner
                groupOwnerAddress = ownerAddress
                if (!sameGroup) {
                    groupFormedAtNanos = System.nanoTime()
                    groupAttemptId.set(currentAttemptId)
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.owner_address_resolved",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        durationMs = currentAttemptId?.let { connectionStarts[it] }?.let {
                            ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                        },
                        result = if (info.isGroupOwner) "LOCAL_GROUP_OWNER_ADDRESS_READY" else "REMOTE_GROUP_OWNER_ADDRESS_READY"
                    )
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.group_formed",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        durationMs = currentAttemptStartedAt?.let { ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L) },
                        result = if (info.isGroupOwner) "GROUP_OWNER" else "GROUP_CLIENT"
                    )
                    publishRuntimeState(WifiDirectStage.GROUP_FORMED)
                }
                clearCandidateFailure(completedPeerAddress, "P2P_GROUP_FORMED")
                updateLocalServiceGroupRole(if (info.isGroupOwner) AdvertisedGroupRole.OWNER else AdvertisedGroupRole.CLIENT)
                requestGroupInfo(current)
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

    @SuppressLint("MissingPermission")
    private fun verifyGroupStillPresent(current: WifiP2pManager.Channel) {
        if (!started || channel !== current || !groupFormed || !hasRequiredPermissions() ||
            !groupVerificationInProgress.compareAndSet(false, true)) return
        val generation = groupVerificationGeneration.incrementAndGet()
        groupVerificationWatchdogJob?.cancel()
        groupVerificationWatchdogJob = scope.launch {
            delay(CONNECTION_INFO_CALLBACK_TIMEOUT_MS)
            if (groupVerificationGeneration.get() == generation &&
                groupVerificationInProgress.compareAndSet(true, false)) {
                groupVerificationWatchdogJob = null
                groupVerificationGeneration.compareAndSet(generation, generation + 1L)
                diagnostics.record(
                    kind = NetworkEventKind.REQUEST_FAILED,
                    operation = "wifi.p2p.group_loss_check_timeout",
                    correlationId = currentAttemptId,
                    transport = "WIFI_DIRECT",
                    result = "GROUP_INFO_CALLBACK_TIMEOUT"
                )
            }
        }
        val p2pManager = manager
        if (p2pManager == null) {
            groupVerificationInProgress.set(false)
            groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
            return
        }
        try {
            p2pManager.requestGroupInfo(current) { group ->
                if (groupVerificationGeneration.get() != generation) return@requestGroupInfo
                groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
                groupVerificationInProgress.set(false)
                if (!started || channel !== current || !groupFormed) return@requestGroupInfo
                if (group != null) {
                    groupLossConfirmations = 0
                    groupLossRecheckJob?.cancel(); groupLossRecheckJob = null
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.group_loss_check",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        result = "CONNECTION_INFO_EMPTY_BUT_GROUP_PRESENT"
                    )
                } else {
                    groupLossConfirmations++
                    diagnostics.record(
                        kind = if (groupLossConfirmations >= GROUP_LOSS_CONFIRMATIONS_REQUIRED)
                            NetworkEventKind.REQUEST_FAILED else NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.group_loss_check",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        attempt = groupLossConfirmations,
                        result = "GROUP_INFO_EMPTY"
                    )
                    if (WifiDirectAdmissionPolicy.groupLossConfirmed(
                            groupLossConfirmations,
                            GROUP_LOSS_CONFIRMATIONS_REQUIRED
                        )) {
                        resetGroupConnections()
                    } else if (WifiDirectAdmissionPolicy.shouldScheduleGroupLossRecheck(
                            groupLossConfirmations,
                            GROUP_LOSS_CONFIRMATIONS_REQUIRED
                        )) {
                        scheduleGroupLossRecheck(current, generation)
                    }
                }
            }
        } catch (failure: Exception) {
            groupVerificationInProgress.set(false)
            groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
            diagnostics.record(
                kind = NetworkEventKind.REQUEST_FAILED,
                operation = "wifi.p2p.group_loss_check",
                correlationId = currentAttemptId,
                transport = "WIFI_DIRECT",
                result = failure.javaClass.simpleName
            )
        }
    }

    private fun scheduleGroupLossRecheck(
        current: WifiP2pManager.Channel,
        verificationGeneration: Long
    ) {
        groupLossRecheckJob?.cancel()
        groupLossRecheckJob = scope.launch {
            delay(GROUP_LOSS_RECHECK_DELAY_MS)
            if (
                groupVerificationGeneration.get() == verificationGeneration &&
                started &&
                channel === current &&
                groupFormed
            ) {
                groupLossRecheckJob = null
                verifyGroupStillPresent(current)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo(current: WifiP2pManager.Channel) {
        // Keep the runtime permission gate beside the platform call; lint cannot
        // infer the target-SDK-aware permission check in PermissionManager.
        if (!started || !groupFormed || !hasRequiredPermissions()) return
        runCatching {
            manager?.requestGroupInfo(current) { group ->
                if (channel !== current || !started || !groupFormed) return@requestGroupInfo
                val memberAddresses = if (groupOwner) {
                    group?.clientList?.mapNotNull { it.deviceAddress?.lowercase() }.orEmpty()
                } else emptyList()
                groupClientAddresses.clear()
                groupClientAddresses.addAll(memberAddresses)
                groupClientCount = memberAddresses.size
                publishRuntimeState(_runtimeState.value.stage, error = _runtimeState.value.lastError)
            }
        }.onFailure {
            diagnostics.record(
                kind = NetworkEventKind.CONNECTION_STAGE,
                operation = "wifi.p2p.group_info_failed",
                correlationId = currentAttemptId,
                transport = "WIFI_DIRECT",
                result = it.javaClass.simpleName
            )
        }
    }

    private fun connectToGroupOwner(address: InetAddress) {
        val host = address.hostAddress ?: return
        if (clientJob?.isActive == true) return
        val sessionGeneration = groupSessionGeneration.current()
        clientJob = scope.launch {
            var attempt = 0
            // The group owner can finish bringing up its TCP listener just after
            // Android reports the P2P group. Keep retrying while this group exists,
            // with a capped delay, rather than giving up after six attempts and
            // waiting for a connection broadcast that may never be sent again.
            while (isActive && started && groupFormed && !groupOwner && sessions.isEmpty()) {
                attempt++
                val connectStarted = System.nanoTime()
                val socket = runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        connect(InetSocketAddress(host, TCP_PORT), CLIENT_SOCKET_CONNECT_TIMEOUT_MS)
                    }
                }.onSuccess {
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.tcp.connected_to_group_owner",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        durationMs = ((System.nanoTime() - connectStarted) / 1_000_000L).coerceAtLeast(0L),
                        attempt = attempt,
                        result = "TCP_CONNECTED"
                    )
                }.onFailure {
                    diagnostics.record(
                        kind = NetworkEventKind.REQUEST_FAILED,
                        operation = "wifi.tcp.connect_to_group_owner",
                        correlationId = currentAttemptId,
                        transport = "WIFI_DIRECT",
                        durationMs = ((System.nanoTime() - connectStarted) / 1_000_000L).coerceAtLeast(0L),
                        attempt = attempt,
                        result = it.javaClass.simpleName
                    )
                }.getOrNull()
                if (socket != null && establishSession(
                        socket,
                        currentAttemptId ?: UUID.randomUUID().toString(),
                        sessionGeneration
                    )) return@launch
                runCatching { socket?.close() }
                delay(if (attempt <= CLIENT_FAST_RETRY_COUNT) CLIENT_RETRY_DELAY_MS else CLIENT_RETRY_MAX_DELAY_MS)
            }
        }
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        val sessionGeneration = groupSessionGeneration.current()
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
            diagnostics.record(
                kind = NetworkEventKind.CONNECTION_STAGE,
                operation = "wifi.tcp.listener_ready",
                correlationId = groupAttemptId.get(),
                transport = "WIFI_DIRECT",
                durationMs = groupFormedAtNanos?.let {
                    ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                },
                result = "CONTROL_LISTENER_READY"
            )
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
                        val attemptId = UUID.randomUUID().toString()
                        diagnostics.record(
                            kind = NetworkEventKind.CONNECTION_STAGE,
                            operation = "wifi.tcp.incoming_socket_accepted",
                            correlationId = attemptId,
                            transport = "WIFI_DIRECT",
                            result = "TCP_ACCEPTED"
                        )
                        launch {
                            try { establishSession(socket, attemptId, sessionGeneration) } finally { handshakeSlots.release() }
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

    private suspend fun establishSession(
        socket: Socket,
        attemptId: String,
        sessionGeneration: Long
    ): Boolean {
        val handshakeStartedAt = System.nanoTime()
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
            diagnostics.record(
                kind = NetworkEventKind.CONNECTION_STAGE,
                operation = "wifi.tcp.handshake_started",
                correlationId = attemptId,
                transport = "WIFI_DIRECT",
                result = "HELLO_SENT"
            )
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
        } catch (failure: Exception) {
            diagnostics.record(
                kind = NetworkEventKind.REQUEST_FAILED,
                operation = "wifi.tcp.handshake_failed",
                correlationId = attemptId,
                transport = "WIFI_DIRECT",
                durationMs = ((System.nanoTime() - handshakeStartedAt) / 1_000_000L).coerceAtLeast(0L),
                result = failure.message ?: failure.javaClass.simpleName
            )
            runCatching { socket.close() }
            return false
        } finally {
            handshakeWatchdog.cancel()
        }
        socket.soTimeout = 0
        if (!started || !groupFormed || socket.isClosed ||
            groupSessionGeneration.current() != sessionGeneration
        ) {
            runCatching { socket.close() }
            return false
        }
        val key = normalize(remote.nodeId)
        val session = PeerSession(remote, socket, input, output)
        // Hold the group token across admission and publication. A reset cannot
        // let an in-flight handshake from the previous P2P group replace a new session.
        val published = groupSessionGeneration.runIfCurrent(sessionGeneration) {
            if (!started || !groupFormed || socket.isClosed) return@runIfCurrent false
            val admission = sessions.admit(key, remote.host, session)
            if (!admission.accepted) {
                diagnostics.record(
                    kind = NetworkEventKind.REQUEST_FAILED,
                    operation = "wifi.tcp.session_rejected",
                    correlationId = attemptId,
                    transport = "WIFI_DIRECT",
                    result = "SESSION_CAPACITY_OR_NODE_ID_CONFLICT"
                )
                return@runIfCurrent false
            }
            admission.replaced?.takeIf { it !== session }?.close(notify = false)
            sessions.runIfCurrent(key, session) {
                val handshakeDurationMs = ((System.nanoTime() - handshakeStartedAt) / 1_000_000L).coerceAtLeast(0L)
                val groupCorrelationId = groupAttemptId.getAndSet(null)
                val totalAttemptDurationMs = connectionStarts.remove(attemptId)?.let {
                    ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                } ?: groupCorrelationId?.let { groupId ->
                    connectionStarts.remove(groupId)?.let {
                        ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
                    }
                }
                recordPeerSessionReady(remote, totalAttemptDurationMs ?: handshakeDurationMs)
                onPeerConnected(remote)
                groupCorrelationId?.let { correlationId ->
                    diagnostics.record(
                        kind = NetworkEventKind.CONNECTION_STAGE,
                        operation = "wifi.p2p.session_ready",
                        correlationId = correlationId,
                        transport = "WIFI_DIRECT",
                        peerNodeId = remote.nodeId,
                        durationMs = totalAttemptDurationMs ?: handshakeDurationMs,
                        result = "TCP_SESSION_ACTIVE; helloMs=$handshakeDurationMs"
                    )
                }
                currentAttemptId = attemptId
                currentPeer = remote.nodeId
                diagnostics.record(
                    kind = NetworkEventKind.CONNECTION_STAGE,
                    operation = "wifi.tcp.session_active",
                    correlationId = attemptId,
                    transport = "WIFI_DIRECT",
                    peerNodeId = remote.nodeId,
                    durationMs = totalAttemptDurationMs ?: handshakeDurationMs,
                    result = "PROTOCOL_${remote.protocolVersion}; helloMs=$handshakeDurationMs"
                )
                publishRuntimeState(WifiDirectStage.SESSION_READY)
                session.startReader()
            }
        } == true
        if (!published) {
            runCatching { socket.close() }
            return false
        }
        ensureHealthProbeLoop()
        return true
    }

    @Synchronized
    private fun ensureHealthProbeLoop() {
        if (healthProbeJob.get() != null) return
        healthProbeJob.set(scope.launch {
            try {
                while (started) {
                    sessions.values.toList().forEach { peerSession ->
                        val startedAt = System.nanoTime()
                        val success = peerSession.probe()
                        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                        sessions.runIfCurrent(normalize(peerSession.peer.nodeId), peerSession) {
                            onPeerHealthSample(
                                peerSession.peer.nodeId,
                                durationMs,
                                success
                            )
                            val invalidated = peerSession.probeFailurePolicy.record(success)
                            recordPeerHealth(peerSession, durationMs)
                            publishRuntimeState(runtimeStageAfterPeerChange(), error = _runtimeState.value.lastError)
                            if (invalidated) {
                                peerSession.close(notify = true, failureReason = "HEALTH_PROBE_FAILURE_THRESHOLD")
                            }
                        }
                    }
                    delay(10_000L)
                }
            } finally {
                healthProbeJob.compareAndSet(coroutineContext[Job], null)
            }
        })
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
        val probeFailurePolicy = ProbeFailurePolicy(PROBE_FAILURES_BEFORE_INVALIDATION)
        @Volatile private var readerJob: Job? = null

        fun startReader() {
            readerJob = scope.launch {
                var failureReason = "REMOTE_SOCKET_CLOSED"
                try {
                    while (isActive && !closed.get()) {
                        when (val frame = WifiDirectFrameCodec.readFrame(input)) {
                            is WifiDirectFrameCodec.Frame.Ack -> {
                                pendingAcks.remove(frame.transferId)?.complete(frame.accepted)
                            }
                            is WifiDirectFrameCodec.Frame.Probe -> writeAck(frame.transferId, true)
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
                    failureReason = "REMOTE_IO_OR_PROTOCOL_FAILURE"
                } finally {
                    close(notify = true, failureReason = failureReason)
                }
            }
        }

        suspend fun probe(): Boolean {
            if (closed.get()) return false
            val transferId = transferIds.incrementAndGet()
            val deferred = CompletableDeferred<Boolean>()
            if (pendingAcks.putIfAbsent(transferId, deferred) != null) return false
            return try {
                withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        if (closed.get()) throw IOException("Session closed")
                        WifiDirectFrameCodec.writeProbe(output, transferId)
                    }
                }
                withTimeoutOrNull(1_500L) { deferred.await() } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                pendingAcks.remove(transferId)
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
                if (!operationFinished.get()) close(notify = true, failureReason = "SEND_OPERATION_TIMEOUT")
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
                    close(notify = true, failureReason = "TRANSPORT_ACK_TIMEOUT")
                    false
                } else {
                    accepted
                }
            } catch (cancelled: CancellationException) {
                // Cancellation alone cannot interrupt a blocking Java socket write.
                // Closing the socket is what releases the IO worker; preserve the
                // caller's cancellation after initiating that cleanup.
                close(notify = true, failureReason = "SEND_CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                close(notify = true, failureReason = "SEND_IO_FAILURE")
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
                close(notify = true, failureReason = "ACK_WRITE_TIMEOUT")
            }
            try {
                synchronized(writeLock) {
                    if (closed.get()) throw IOException("Session closed")
                    WifiDirectFrameCodec.writeAck(output, transferId, accepted)
                }
            } catch (_: Exception) {
                close(notify = true, failureReason = "ACK_WRITE_FAILURE")
            } finally {
                watchdog.cancel()
            }
        }

        fun close(notify: Boolean, failureReason: String = "SESSION_CLOSED") {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            readerJob?.cancel()
            pendingAcks.values.forEach { it.complete(false) }
            pendingAcks.clear()
            sessions.remove(normalize(peer.nodeId), this) {
                if (notify) {
                    recordPeerSessionClosed(peer, failureReason)
                    onPeerDisconnected(peer.nodeId)
                }
            }
            publishRuntimeState(runtimeStageAfterPeerChange())
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
        groupSessionGeneration.advance()
        resolveGroupAttemptAsStopped("GROUP_DISCONNECTED")
        connectAttempts.clear()
        candidateRetryJobs.values.forEach { it.cancel() }
        candidateRetryJobs.clear()
        candidateFailureCounts.clear()
        candidateRetryAfter.clear()
        connectionStarts.clear()
        connectionInfoRequestToken.incrementAndGet()
        connectionInfoRequestInProgress.set(false)
        connectionInfoWatchdogJob?.cancel(); connectionInfoWatchdogJob = null
        connectionAttemptInProgress.set(false)
        connectionAttemptGeneration.incrementAndGet()
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        connectRequestCallbackJob?.cancel(); connectRequestCallbackJob = null
        synchronized(discoveryLock) {
            discoveryRequestGeneration.incrementAndGet()
            discoveryRequestInProgress.set(false)
            serviceDiscoveryActive = false
            discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        }
        groupFormed = false
        groupOwner = false
        groupClientCount = 0
        groupClientAddresses.clear()
        groupLossConfirmations = 0
        groupFormedAtNanos = null
        groupVerificationGeneration.incrementAndGet()
        groupVerificationInProgress.set(false)
        groupVerificationWatchdogJob?.cancel(); groupVerificationWatchdogJob = null
        groupLossRecheckJob?.cancel(); groupLossRecheckJob = null
        groupOwnerAddress = null
        closeServer()
        closeMediaServer()
        closeActiveMediaSockets()
        clientJob?.cancel(); clientJob = null
        closeAllSessions("GROUP_DISCONNECTED")
        if (started && hasRequiredPermissions()) {
            if (localService == null || serviceRequest == null) installServiceAndDiscover()
            else updateLocalServiceGroupRole(AdvertisedGroupRole.NONE)
        }
    }

    private fun resolveGroupAttemptAsStopped(result: String) {
        groupAttemptId.getAndSet(null)?.let { attemptId ->
            diagnostics.record(
                kind = NetworkEventKind.CONNECTION_STAGE,
                operation = "wifi.p2p.stopped",
                correlationId = attemptId,
                transport = "WIFI_DIRECT",
                result = result
            )
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

    private fun closeAllSessions(reason: String = "SESSION_CLOSED") {
        sessions.values.toList().forEach { it.close(notify = true, failureReason = reason) }
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
        private const val SERVICE_CALLBACK_TIMEOUT_MS = 8_000L
        private const val DISCOVERY_CALLBACK_TIMEOUT_MS = 8_000L
        private const val CANDIDATE_RETENTION_MS = 60_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
        private const val CLIENT_SOCKET_CONNECT_TIMEOUT_MS = 1_200
        private const val CONNECT_NEGOTIATION_NOTICE_MS = 30_000L
        private const val CONNECT_REQUEST_CALLBACK_TIMEOUT_MS = 10_000L
        private const val CONNECT_NEGOTIATION_TIMEOUT_MS = 120_000L
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
        private const val GROUP_LOSS_RECHECK_DELAY_MS = 2_000L
        private const val GROUP_LOSS_CONFIRMATIONS_REQUIRED = 2
        private const val SERVER_BACKLOG = 8
        private const val MAX_DEVICE_NAME_CHARS = 64
        private const val MAX_CONCURRENT_RECEIVE_PACKETS = 4
        private const val PROBE_FAILURES_BEFORE_INVALIDATION = 2
        private const val MAX_CONCURRENT_HANDSHAKES = 4
        private const val MAX_WIFI_SESSIONS = 8
        private const val MAX_DISCOVERED_SERVICE_DEVICES = 64
        private const val MAX_WIFI_PEER_DIAGNOSTICS = 64
        private const val PEER_RUNTIME_RETENTION_MS = 5 * 60 * 1000L
    }
}
