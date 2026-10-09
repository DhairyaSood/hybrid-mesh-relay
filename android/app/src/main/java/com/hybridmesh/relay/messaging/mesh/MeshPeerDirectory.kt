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
    val meshProtocolVersion: Int = 0,
    val canRelay: Boolean = false,
    val canStoreForward: Boolean = false
) {
    fun toTransportPeer(): MeshTransportPeer = MeshTransportPeer(
        nodeId = nodeId,
        bleAddress = bleAddress,
        wifiAddress = wifiAddress ?: wifiHost?.let { "$it:${wifiPort ?: 0}" }
    )

    fun hasAnyEndpoint(): Boolean =
        !bleAddress.isNullOrBlank() || (!wifiHost.isNullOrBlank() && (wifiPort ?: 0) > 0)
}

/**
 * Merges observations from independent radios under the stable Neyra node ID.
 * Endpoint observations are runtime-only; Room schema and persisted identity stay unchanged.
 */
class MeshPeerDirectory {
    private val lock = Any()
    private val peers = LinkedHashMap<String, MeshPeer>()
    private val _snapshot = MutableStateFlow<List<MeshPeer>>(emptyList())
    val snapshot: StateFlow<List<MeshPeer>> = _snapshot.asStateFlow()

    fun replaceBlePeers(observed: List<BlePeer>) = synchronized(lock) {
        val byNode = observed.associateBy { it.nodeId.normalized() }
        peers.entries.toList().forEach { (key, old) ->
            val ble = byNode[key]
            val updated = if (ble == null) old.copy(bleAddress = null, rssi = -127) else old.copy(
                deviceName = ble.deviceName.ifBlank { old.deviceName },
                bleAddress = ble.address.takeUnless { it.isBlank() || it.equals("Unknown", true) },
                rssi = ble.rssi,
                lastSeen = maxOf(old.lastSeen, ble.lastSeen),
                meshProtocolVersion = maxOf(old.meshProtocolVersion, ble.meshProtocolVersion),
                canRelay = ble.canRelay || old.canRelay,
                canStoreForward = ble.canStoreForward || old.canStoreForward
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
                meshProtocolVersion = maxOf(previous?.meshProtocolVersion ?: 0, ble.meshProtocolVersion),
                canRelay = ble.canRelay || (previous?.canRelay == true),
                canStoreForward = ble.canStoreForward || (previous?.canStoreForward == true)
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
            meshProtocolVersion = maxOf(previous?.meshProtocolVersion ?: 0, protocolVersion),
            canRelay = canRelay || (previous?.canRelay == true),
            canStoreForward = canStoreForward || (previous?.canStoreForward == true)
        )
        publishLocked()
    }

    fun removeWifi(nodeId: String) = synchronized(lock) {
        val key = nodeId.normalized()
        val previous = peers[key] ?: return@synchronized
        val updated = previous.copy(wifiAddress = null, wifiHost = null, wifiPort = null)
        if (updated.hasAnyEndpoint()) peers[key] = updated else peers.remove(key)
        publishLocked()
    }

    fun findByBleAddress(address: String): MeshPeer? = synchronized(lock) {
        peers.values.firstOrNull { it.bleAddress.equals(address, ignoreCase = true) }
    }

    fun findByNodeId(nodeId: String): MeshPeer? = synchronized(lock) {
        peers[nodeId.normalized()]
    }

    private fun publishLocked() {
        _snapshot.value = peers.values
            .filter { it.hasAnyEndpoint() }
            .sortedBy { it.nodeId.lowercase(Locale.US) }
    }

    private fun String.normalized(): String = trim().lowercase(Locale.US)
}
