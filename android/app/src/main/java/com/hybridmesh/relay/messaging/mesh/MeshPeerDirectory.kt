package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.ble.BlePeer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A transport-independent view of a Neyra node and its currently observed endpoints. */
data class MeshPeer(
    val nodeId: String,
    val deviceName: String,
    val bleAddress: String? = null,
    val wifiAddress: String? = null,
    val wifiHost: String? = null,
    val wifiPort: Int? = null,
    val rssi: Int = -127,
    val lastSeen: Long = 0L,
    val bleProtocolVersion: Int = 0,
    val wifiProtocolVersion: Int = 0,
    val bleSupportsAttachmentHopIdentity: Boolean = false,
    val bleCanRelay: Boolean = false,
    val bleCanStoreForward: Boolean = false,
    val wifiCanRelay: Boolean = false,
    val wifiCanStoreForward: Boolean = false
) {
    val meshProtocolVersion: Int
        get() = maxOf(
            if (!bleAddress.isNullOrBlank()) bleProtocolVersion else 0,
            if (!wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0) wifiProtocolVersion else 0
        )

    fun protocolVersionFor(transport: MeshTransportKind): Int = when (transport) {
        MeshTransportKind.BLE_GATT -> bleProtocolVersion.takeIf { !bleAddress.isNullOrBlank() } ?: 0
        MeshTransportKind.WIFI_DIRECT -> wifiProtocolVersion.takeIf {
            !wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0
        } ?: 0
        MeshTransportKind.LORA -> 0
    }

    val canRelay: Boolean
        get() = (!bleAddress.isNullOrBlank() && bleCanRelay) ||
            (!wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0 && wifiCanRelay)
    val canStoreForward: Boolean
        get() = (!bleAddress.isNullOrBlank() && bleCanStoreForward) ||
            (!wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0 && wifiCanStoreForward)

    fun toTransportPeer(): MeshTransportPeer = MeshTransportPeer(
        nodeId = nodeId,
        bleAddress = bleAddress,
        wifiAddress = wifiAddress ?: wifiHost?.let { "$it:${wifiPort ?: 0}" },
        bleSupportsAttachmentHopIdentity = bleSupportsAttachmentHopIdentity
    )

    fun hasAnyEndpoint(): Boolean =
        !bleAddress.isNullOrBlank() || (!wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0)
}

/**
 * Merges observations from independent radios under the stable Neyra node ID.
 * Endpoint observations are runtime-only; Room schema and persisted identity stay unchanged.
 */
class MeshPeerDirectory {
    private data class RememberedBleAddress(
        val nodeId: String,
        val lastSeen: Long
    )

    private data class ActiveBleConnection(
        val connectionId: Long,
        var nodeId: String?
    )

    private val lock = Any()
    private val peers = LinkedHashMap<String, MeshPeer>()
    // Keep just the recent address-to-identity association for ingress. Live route
    // snapshots still drop peers at the scanner's shorter stale timeout.
    private val recentBleAddresses = LinkedHashMap<String, RememberedBleAddress>()
    // Discovery is allowed to expire while GATT stays connected. Pin the identity
    // to the GATT session and only release it for that session's disconnect event.
    private val activeBleConnections = LinkedHashMap<String, ActiveBleConnection>()
    private val _snapshot = MutableStateFlow<List<MeshPeer>>(emptyList())
    val snapshot: StateFlow<List<MeshPeer>> = _snapshot.asStateFlow()

    fun replaceBlePeers(
        observed: List<BlePeer>,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        observed.forEach { ble ->
            val address = ble.address.takeUnless { it.isBlank() || it.equals("Unknown", true) }
                ?: return@forEach
            recentBleAddresses[address.normalizedAddress()] = RememberedBleAddress(
                nodeId = ble.nodeId,
                lastSeen = ble.lastSeen.takeIf { it > 0L } ?: now
            )
            activeBleConnections[address.normalizedAddress()]?.let { connection ->
                // A connected session can learn an identity later if discovery
                // had not completed when Android reported the connection.
                if (connection.nodeId == null) connection.nodeId = ble.nodeId
            }
        }
        pruneRecentBleAddresses(now)

        val byNode = observed.associateBy { it.nodeId.normalized() }
        peers.entries.toList().forEach { (key, old) ->
            val ble = byNode[key]
            val updated = if (ble == null) old.copy(
                bleAddress = null,
                bleProtocolVersion = 0,
                bleSupportsAttachmentHopIdentity = false,
                bleCanRelay = false,
                bleCanStoreForward = false,
                rssi = -127
            ) else old.copy(
                deviceName = ble.deviceName.ifBlank { old.deviceName },
                bleAddress = ble.address.takeUnless { it.isBlank() || it.equals("Unknown", true) },
                rssi = ble.rssi,
                lastSeen = maxOf(old.lastSeen, ble.lastSeen),
                bleProtocolVersion = ble.meshProtocolVersion,
                bleSupportsAttachmentHopIdentity = ble.supportsAttachmentHopIdentity,
                bleCanRelay = ble.canRelay,
                bleCanStoreForward = ble.canStoreForward
            )
            if (updated.hasAnyEndpoint()) peers[key] = updated else peers.remove(key)
        }
        observed.forEach { ble ->
            val key = ble.nodeId.normalized()
            val previous = peers[key]
            peers[key] = MeshPeer(
                nodeId = ble.nodeId,
                deviceName = ble.deviceName,
                bleAddress = ble.address.takeUnless { it.isBlank() || it.equals("Unknown", true) },
                wifiAddress = previous?.wifiAddress,
                wifiHost = previous?.wifiHost,
                wifiPort = previous?.wifiPort,
                rssi = ble.rssi,
                lastSeen = maxOf(previous?.lastSeen ?: 0L, ble.lastSeen),
                bleProtocolVersion = ble.meshProtocolVersion,
                wifiProtocolVersion = previous?.wifiProtocolVersion ?: 0,
                bleSupportsAttachmentHopIdentity = ble.supportsAttachmentHopIdentity,
                bleCanRelay = ble.canRelay,
                bleCanStoreForward = ble.canStoreForward,
                wifiCanRelay = previous?.wifiCanRelay ?: false,
                wifiCanStoreForward = previous?.wifiCanStoreForward ?: false
            )
        }
        publishLocked()
    }

    fun observeWifi(
        nodeId: String,
        deviceName: String,
        host: String,
        port: Int,
        protocolVersion: Int,
        canRelay: Boolean,
        canStoreForward: Boolean,
        observedAt: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (nodeId.isBlank() || host.isBlank() || port !in 1..65535) return@synchronized
        val key = nodeId.normalized()
        val previous = peers[key]
        peers[key] = MeshPeer(
            nodeId = nodeId,
            deviceName = deviceName.ifBlank { previous?.deviceName.orEmpty() },
            bleAddress = previous?.bleAddress,
            wifiAddress = "$host:$port",
            wifiHost = host,
            wifiPort = port,
            rssi = previous?.rssi ?: -127,
            lastSeen = maxOf(previous?.lastSeen ?: 0L, observedAt),
            bleProtocolVersion = previous?.bleProtocolVersion ?: 0,
            wifiProtocolVersion = protocolVersion,
            bleSupportsAttachmentHopIdentity = previous?.bleSupportsAttachmentHopIdentity ?: false,
            bleCanRelay = previous?.bleCanRelay ?: false,
            bleCanStoreForward = previous?.bleCanStoreForward ?: false,
            wifiCanRelay = canRelay,
            wifiCanStoreForward = canStoreForward
        )
        publishLocked()
    }

    fun removeWifi(nodeId: String) = synchronized(lock) {
        val key = nodeId.normalized()
        val previous = peers[key] ?: return@synchronized
        val updated = previous.copy(
            wifiAddress = null,
            wifiHost = null,
            wifiPort = null,
            wifiProtocolVersion = 0,
            wifiCanRelay = false,
            wifiCanStoreForward = false
        )
        if (updated.hasAnyEndpoint()) peers[key] = updated else peers.remove(key)
        publishLocked()
    }

    fun findLiveNodeIdByBleAddress(address: String): String? = synchronized(lock) {
        peers.values.firstOrNull { it.bleAddress.equals(address, ignoreCase = true) }
            ?.nodeId
    }

    fun onBleConnectionEstablished(address: String, connectionId: Long): String? = synchronized(lock) {
        val key = address.normalizedAddress().takeIf { it.isNotBlank() } ?: return@synchronized null
        val existing = activeBleConnections[key]
        if (existing?.connectionId == connectionId) {
            if (existing.nodeId == null) existing.nodeId = findLiveNodeIdLocked(key)
            return@synchronized existing.nodeId
        }
        // Do not evict a still-active connection to make room: fail closed if the
        // platform ever reports an implausible number of simultaneous GATT peers.
        if (existing == null && activeBleConnections.size >= MAX_ACTIVE_BLE_CONNECTIONS) {
            return@synchronized null
        }
        val binding = ActiveBleConnection(
            connectionId = connectionId,
            // A new connection may bind only to a live discovery observation.
            // Reusing the short recent cache here could map a recycled Bluetooth
            // address to its previous owner.
            nodeId = findLiveNodeIdLocked(key)
        )
        activeBleConnections[key] = binding
        binding.nodeId
    }

    /** A delayed disconnect from an old GATT session cannot clear a newer binding. */
    fun onBleConnectionClosed(address: String, connectionId: Long): Boolean = synchronized(lock) {
        val key = address.normalizedAddress()
        val current = activeBleConnections[key] ?: return@synchronized false
        if (current.connectionId != connectionId) return@synchronized false
        activeBleConnections.remove(key)
        true
    }

    fun activeBleConnectionCount(): Int = synchronized(lock) { activeBleConnections.size }

    fun findNodeIdByBleAddress(address: String): String? = synchronized(lock) {
        val key = address.normalizedAddress()
        activeBleConnections[key]?.let { connection ->
            if (connection.nodeId == null) connection.nodeId = findLiveNodeIdLocked(key)
            return@synchronized connection.nodeId
        }
        findDiscoveredNodeIdLocked(key, System.currentTimeMillis())
    }

    fun findByNodeId(nodeId: String): MeshPeer? = synchronized(lock) {
        peers[nodeId.normalized()]
    }

    private fun publishLocked() {
        _snapshot.value = peers.values
            .filter { it.hasAnyEndpoint() }
            .sortedBy { it.nodeId.lowercase(Locale.US) }
    }

    private fun pruneRecentBleAddresses(now: Long) {
        recentBleAddresses.entries.removeIf { now - it.value.lastSeen > BLE_INGRESS_IDENTITY_RETENTION_MS }
        while (recentBleAddresses.size > MAX_REMEMBERED_BLE_ADDRESSES) {
            val oldest = recentBleAddresses.minByOrNull { it.value.lastSeen } ?: break
            recentBleAddresses.remove(oldest.key)
        }
    }

    private fun findDiscoveredNodeIdLocked(addressKey: String, now: Long): String? {
        pruneRecentBleAddresses(now)
        findLiveNodeIdLocked(addressKey)?.let { return it }
        return recentBleAddresses[addressKey]?.nodeId
    }

    private fun findLiveNodeIdLocked(addressKey: String): String? =
        peers.values.firstOrNull { it.bleAddress?.normalizedAddress() == addressKey }?.nodeId

    private fun String.normalized(): String = trim().lowercase(Locale.US)
    private fun String.normalizedAddress(): String = trim().uppercase(Locale.US)

    private companion object {
        const val BLE_INGRESS_IDENTITY_RETENTION_MS = 2 * 60 * 1_000L
        const val MAX_REMEMBERED_BLE_ADDRESSES = 256
        const val MAX_ACTIVE_BLE_CONNECTIONS = 128
    }
}
