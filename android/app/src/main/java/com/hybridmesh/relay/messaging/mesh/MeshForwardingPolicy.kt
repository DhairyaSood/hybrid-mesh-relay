package com.hybridmesh.relay.messaging.mesh

import java.util.Locale

/**
 * Bounded managed-flooding peer selection over transport-independent Neyra peers.
 * The policy chooses logical next hops; TransportSelector chooses the bearer.
 */
object MeshForwardingPolicy {
    const val MAX_FANOUT = 3

    fun select(
        peers: List<MeshPeer>,
        localNodeId: String,
        destinationNodeId: String,
        excludeNodeId: String?,
        excludeAddress: String?,
        packetId: String = "",
        maxFanout: Int = MAX_FANOUT,
        isTransportUsable: (MeshPeer) -> Boolean = { it.hasAnyEndpoint() }
    ): List<MeshPeer> {
        val local = localNodeId.trim()
        val destination = destinationNodeId.trim()
        val excludedNode = excludeNodeId?.trim()
        val excludedAddress = excludeAddress?.trim()

        val usable = peers.asSequence()
            .filter { it.hasAnyEndpoint() && isTransportUsable(it) }
            .filter { !it.nodeId.equals(local, true) }
            .filter { excludedNode == null || !it.nodeId.equals(excludedNode, true) }
            .filter {
                excludedAddress == null ||
                    (!it.bleAddress.equals(excludedAddress, true) &&
                     !it.wifiAddress.equals(excludedAddress, true) &&
                     !it.nodeId.equals(excludedAddress.removePrefix("wifi:"), true))
            }
            // Protocol 0 is the oldest discovery format and protocol 1 is the
            // pre-trace BLE mesh. The transport-usability predicate supplied by
            // MeshEngine keeps these peers on BLE only; v2 remains required for
            // Wi-Fi Direct and route-trace packets.
            .filter { it.meshProtocolVersion in 0..MeshPacketCodec.PROTOCOL_VERSION }
            .distinctBy { it.nodeId.lowercase(Locale.US) }
            .toList()

        usable.firstOrNull { it.nodeId.equals(destination, true) }?.let { return listOf(it) }

        val eligible = usable.filter { it.canRelay && it.canStoreForward }
        if (eligible.isEmpty()) return emptyList()

        // RSSI values from different bearers are not comparable. Retain the
        // BLE-strength preference only when every candidate has a BLE endpoint;
        // mixed-bearer candidate sets use packet-specific stable distribution.
        val compareBleRssi = eligible.all { !it.bleAddress.isNullOrBlank() }
        return eligible.sortedWith(
            compareByDescending<MeshPeer> {
                val hash = (packetId + "|" + it.nodeId).hashCode().toUInt().toLong()
                val radioScore = if (compareBleRssi) it.rssi * 1_000L else 0L
                radioScore + (hash % 10_000L)
            }.thenBy { it.nodeId.lowercase(Locale.US) }
        ).take(maxFanout.coerceIn(1, MAX_FANOUT))
    }
}
