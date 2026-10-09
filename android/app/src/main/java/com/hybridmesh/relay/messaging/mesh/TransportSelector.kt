package com.hybridmesh.relay.messaging.mesh

/**
 * Deterministic next-hop selector. Only already-usable sessions are considered;
 * this component never starts radio discovery or P2P group negotiation to send a
 * single packet. A warm Wi-Fi socket is preferred because the current BLE sender
 * pays GATT connection/service/notification setup per send. BLE remains the
 * immediate fallback if the Wi-Fi send fails.
 */
class TransportSelector(
    private val transports: () -> List<MeshTransport>
) {
    fun candidates(peer: MeshPeer): List<Pair<MeshTransport, MeshTransportPeer>> {
        val endpoint = peer.toTransportPeer()
        val available = transports().filter { it.isAvailable() && it.canReach(endpoint) }
        val wifi = available.firstOrNull { it.kind == MeshTransportKind.WIFI_DIRECT }
        val ble = available.firstOrNull { it.kind == MeshTransportKind.BLE_GATT }
        val ordered = when {
            wifi != null -> listOfNotNull(wifi, ble) + available.filter {
                it !== wifi && it !== ble
            }
            ble != null -> listOf(ble) + available.filter { it !== ble }
            else -> available.sortedBy { it.kind.ordinal }
        }
        return ordered.map { it to endpoint }
    }
}
