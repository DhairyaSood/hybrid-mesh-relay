package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.ble.BlePeer
import java.util.Locale

/**
 * Bounded managed-flooding peer selection.
 *
 * A directly reachable destination is preferred. Otherwise a small,
 * deterministic-but-packet-specific relay set is selected so the same few
 * high-RSSI peers do not become permanent flood hubs.
 */
object MeshForwardingPolicy {
    const val MAX_FANOUT = 3

    fun select(
        peers: List<BlePeer>,
        localNodeId: String,
        destinationNodeId: String,
        excludeNodeId: String?,
        excludeAddress: String?,
        packetId: String = "",
        maxFanout: Int = MAX_FANOUT
    ): List<BlePeer> {
        val local = localNodeId.trim()
        val destination = destinationNodeId.trim()
        val excludedNode = excludeNodeId?.trim()
        val excludedAddress = excludeAddress?.trim()

        val usable = peers.asSequence()
            .filter { it.address.isNotBlank() && !it.address.equals("Unknown", true) }
            .filter { !it.nodeId.equals(local, true) }
            .filter { excludedNode == null || !it.nodeId.equals(excludedNode, true) }
            .filter { excludedAddress == null || !it.address.equals(excludedAddress, true) }
            .filter { it.meshProtocolVersion >= MeshPacketCodec.PROTOCOL_VERSION }
            .distinctBy { it.address.uppercase(Locale.US) }
            .toList()

        usable.firstOrNull { it.nodeId.equals(destination, true) }?.let {
            return listOf(it)
        }

        val eligible = usable.filter { it.canRelay && it.canStoreForward }
        if (eligible.isEmpty()) return emptyList()

        return eligible
            .sortedWith(
                compareByDescending<BlePeer> {
                    // Prefer stronger links, then distribute packets across peers.
                    // Hash contribution is bounded and stable for a given packet.
                    val hash = (packetId + "|" + it.nodeId).hashCode().toUInt().toLong()
                    it.rssi * 1_000L + (hash % 10_000L)
                }.thenBy { it.nodeId.lowercase(Locale.US) }
            )
            .take(maxFanout.coerceIn(1, MAX_FANOUT))
    }
}
