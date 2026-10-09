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
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import com.hybridmesh.relay.permissions.PermissionManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
    private val onPeerDisconnected: (nodeId: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val identityStore = IdentityStore.getInstance(appContext)
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val handshakeSlots = Semaphore(MAX_CONCURRENT_HANDSHAKES)
    private val connectAttempts = ConcurrentHashMap.newKeySet<String>()
    private val advertisedServiceDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    private val compatibleServiceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val transferIds = AtomicLong(System.nanoTime())
    private val serviceSetupInProgress = AtomicBoolean(false)
    private val serviceSetupGeneration = AtomicLong(0L)

    @Volatile private var started = false
    @Volatile private var receiverRegistered = false
    @Volatile private var groupFormed = false
    @Volatile private var groupOwner = false
    @Volatile private var groupOwnerAddress: InetAddress? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var clientJob: Job? = null
    @Volatile private var discoveryJob: Job? = null
    @Volatile private var serviceRetryJob: Job? = null
    @Volatile private var localService: WifiP2pDnsSdServiceInfo? = null
    @Volatile private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        resetRuntimeForP2pLoss(clearChannel = false)
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        if (networkInfo?.isConnected == false) resetGroupConnections()
                    } else {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == false) resetGroupConnections()
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
        discoveryJob?.cancel(); discoveryJob = null
        serviceRetryJob?.cancel(); serviceRetryJob = null
        clientJob?.cancel(); clientJob = null
        closeServer()
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
    }

    fun isStarted(): Boolean = started && channel != null && hasRequiredPermissions()
    fun isDiscoveryActive(): Boolean =
        isStarted() && serviceRequest != null && discoveryJob?.isActive == true
    fun isAvailable(): Boolean = sessions.isNotEmpty()
    fun connectedPeerCount(): Int = sessions.size
    fun hasPeer(nodeId: String): Boolean = sessions.containsKey(normalize(nodeId))

    suspend fun sendToPeer(nodeId: String, payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > MeshPacketCodec.MAX_PACKET_BYTES) return false
        return sessions[normalize(nodeId)]?.send(payload, transferIds.incrementAndGet()) ?: false
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
            beginDiscovery()
            return
        }
        if (!serviceSetupInProgress.compareAndSet(false, true)) return
        val generation = serviceSetupGeneration.incrementAndGet()
        val service = runCatching {
            WifiP2pDnsSdServiceInfo.newInstance(
                "neyra",
                "_neyra._tcp",
                mapOf("v" to MeshPacketCodec.PROTOCOL_VERSION.toString())
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
                        if (record?.get("v") == MeshPacketCodec.PROTOCOL_VERSION.toString()) {
                            if (compatibleServiceAddresses.size >= MAX_DISCOVERED_SERVICE_DEVICES) {
                                compatibleServiceAddresses.clear()
                                advertisedServiceDevices.clear()
                            }
                            compatibleServiceAddresses.add(key)
                            advertisedServiceDevices[key]?.let { if (started && !groupFormed) connectToService(it) }
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
        discoveryJob?.cancel(); discoveryJob = null
        clientJob?.cancel(); clientJob = null
        closeServer()
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
        if (!started || groupFormed || !hasRequiredPermissions()) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive && started && !groupFormed) {
                val current = channel
                if (current != null) {
                    manager?.discoverServices(current, actionListener { })
                }
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToService(device: WifiP2pDevice) {
        if (!started || groupFormed || !hasRequiredPermissions()) return
        val key = device.deviceAddress?.lowercase() ?: return
        if (!connectAttempts.add(key)) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = (identityStore.getIdentity().nodeId.hashCode().ushr(1) % 16)
        }
        val current = channel ?: run { connectAttempts.remove(key); return }
        manager?.connect(current, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Keep this peer debounced until group formation. If negotiation
                // stalls without a broadcast, allow a later discovery cycle to retry.
                scope.launch {
                    delay(CONNECT_NEGOTIATION_TIMEOUT_MS)
                    if (!groupFormed) connectAttempts.remove(key)
                }
            }
            override fun onFailure(reason: Int) {
                connectAttempts.remove(key)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (!started || !hasRequiredPermissions()) return
        val current = channel ?: return
        manager?.requestConnectionInfo(current) connectionInfoCallback@{ info: WifiP2pInfo ->
            if (!started) return@connectionInfoCallback
            val ownerAddress = info.groupOwnerAddress
            if (!info.groupFormed || ownerAddress == null) {
                connectAttempts.clear()
                groupFormed = false
                groupOwner = false
                groupOwnerAddress = null
                closeServer()
                clientJob?.cancel(); clientJob = null
                closeAllSessions()
                beginDiscovery()
                return@connectionInfoCallback
            }

            connectAttempts.clear()
            groupFormed = true
            groupOwner = info.isGroupOwner
            groupOwnerAddress = ownerAddress
            discoveryJob?.cancel(); discoveryJob = null
            if (info.isGroupOwner) {
                startServer()
            } else {
                closeServer()
                connectToGroupOwner(ownerAddress)
            }
        }
    }

    private fun connectToGroupOwner(address: InetAddress) {
        val host = address.hostAddress ?: return
        if (clientJob?.isActive == true) return
        clientJob = scope.launch {
            var attempt = 0
            while (isActive && started && groupFormed && !groupOwner && sessions.isEmpty() && attempt < CLIENT_CONNECT_ATTEMPTS) {
                attempt++
                val socket = runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        connect(InetSocketAddress(host, TCP_PORT), SOCKET_CONNECT_TIMEOUT_MS)
                    }
                }.getOrNull()
                if (socket != null && establishSession(socket)) return@launch
                runCatching { socket?.close() }
                delay(CLIENT_RETRY_DELAY_MS)
            }
        }
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            var server: ServerSocket? = null
            var bindAttempt = 0
            while (isActive && started && groupFormed && groupOwner && server == null && bindAttempt < SERVER_BIND_ATTEMPTS) {
                bindAttempt++
                server = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", TCP_PORT), SERVER_BACKLOG)
                    }
                }.getOrNull()
                if (server == null) delay(SERVER_BIND_RETRY_MS)
            }
            val activeServer = server ?: return@launch
            serverSocket = activeServer
            try {
                while (isActive && started && groupFormed && groupOwner) {
                    val socket = activeServer.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                    }
                    if (!handshakeSlots.tryAcquire()) {
                        runCatching { socket.close() }
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
            if (!sessions.containsKey(key) && sessions.size >= MAX_WIFI_SESSIONS) {
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
        groupFormed = false
        groupOwner = false
        groupOwnerAddress = null
        closeServer()
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

    private fun closeAllSessions() {
        sessions.values.toList().forEach { it.close(notify = true) }
    }

    private fun normalize(nodeId: String): String = nodeId.trim().lowercase()

    private fun actionListener(successAction: () -> Unit): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = successAction()
            override fun onFailure(reason: Int) = Unit
        }

    companion object {
        const val TCP_PORT = 38_991
        private const val DISCOVERY_INTERVAL_MS = 15_000L
        private const val SERVICE_SETUP_RETRY_MS = 4_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
        private const val CONNECT_NEGOTIATION_TIMEOUT_MS = 20_000L
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val TRANSPORT_ACK_TIMEOUT_MS = 8_000L
        private const val SEND_OPERATION_TIMEOUT_MS = 10_000L
        private const val ACK_WRITE_TIMEOUT_MS = 5_000L
        private const val MAX_CONCURRENT_SENDS_PER_SESSION = 2
        private const val CLIENT_RETRY_DELAY_MS = 800L
        private const val CLIENT_CONNECT_ATTEMPTS = 6
        private const val CLIENT_RECONNECT_DELAY_MS = 750L
        private const val SERVER_BIND_ATTEMPTS = 5
        private const val SERVER_BIND_RETRY_MS = 500L
        private const val SERVER_BACKLOG = 8
        private const val MAX_DEVICE_NAME_CHARS = 64
        private const val MAX_CONCURRENT_RECEIVE_PACKETS = 4
        private const val MAX_CONCURRENT_HANDSHAKES = 4
        private const val MAX_WIFI_SESSIONS = 8
        private const val MAX_DISCOVERED_SERVICE_DEVICES = 64
    }
}
