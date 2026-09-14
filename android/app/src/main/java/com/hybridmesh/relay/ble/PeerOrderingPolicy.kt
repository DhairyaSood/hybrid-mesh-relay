package com.hybridmesh.relay.ble

import com.hybridmesh.relay.messaging.data.PeerEntity
import java.util.Locale

/**
 * Shared ordering contract for live BLE peers. First discovery is stable; RSSI is telemetry only.
 * Existing peers never move because of normal RSSI fluctuations.
 */
object PeerOrderingPolicy {
    fun stableLive(peers: List<BlePeer>): List<BlePeer> = peers

    fun mergeKnownWithLive(
        known: List<PeerEntity>,
        live: List<BlePeer>
    ): List<Pair<PeerEntity, BlePeer?>> {
        val liveById = live.associateBy { it.nodeId.uppercase(Locale.US) }
        val rows = mutableListOf<Pair<PeerEntity, BlePeer?>>()
        val knownIds = HashSet<String>()

        known.forEach { peer ->
            val normalized = peer.nodeId.uppercase(Locale.US)
            knownIds += normalized
            rows += peer to liveById[normalized]
        }

        live.forEach { peer ->
            val normalized = peer.nodeId.uppercase(Locale.US)
            if (normalized !in knownIds) {
                rows += PeerEntity(
                    nodeId = normalized,
                    displayName = peer.deviceName,
                    deviceType = peer.deviceType.name,
                    address = peer.address.takeIf { it != "Unknown" },
                    lastRssi = peer.rssi,
                    lastSeenAt = peer.lastSeen
                ) to peer
            }
        }
        return rows
    }
}
