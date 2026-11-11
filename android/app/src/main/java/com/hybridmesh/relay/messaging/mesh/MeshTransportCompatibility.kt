package com.hybridmesh.relay.messaging.mesh

/**
 * Per-peer bearer checks used by next-hop selection. Only transports the selector
 * reports as available and able to reach the peer are considered.
 */
internal object MeshTransportCompatibility {
    /** Legacy/unknown packet peers are supported only through the BLE v1 codec. */
    fun canCarryPacket(peer: MeshPeer, kind: MeshTransportKind): Boolean =
        supportsTrace(peer, kind) || kind == MeshTransportKind.BLE_GATT

    fun supportsTrace(peer: MeshPeer, kind: MeshTransportKind): Boolean =
        peer.protocolVersionFor(kind) >= MeshPacketCodec.PROTOCOL_VERSION

    fun canRelayOver(peer: MeshPeer, kind: MeshTransportKind): Boolean = when (kind) {
        MeshTransportKind.BLE_GATT -> peer.bleCanRelay && peer.bleCanStoreForward
        MeshTransportKind.WIFI_DIRECT -> peer.wifiCanRelay && peer.wifiCanStoreForward
        MeshTransportKind.LORA -> false
    }

    fun hasCompatibleTransport(peer: MeshPeer, selector: TransportSelector): Boolean =
        selector.candidates(peer).any { (transport, _) -> canCarryPacket(peer, transport.kind) }

    fun hasCompatibleRelayTransport(peer: MeshPeer, selector: TransportSelector): Boolean =
        selector.candidates(peer).any { (transport, _) ->
            canRelayOver(peer, transport.kind) && canCarryPacket(peer, transport.kind)
        }

    fun hasTraceCompatibleTransport(peer: MeshPeer, selector: TransportSelector): Boolean =
        selector.candidates(peer).any { (transport, _) -> supportsTrace(peer, transport.kind) }

    fun hasTraceCompatibleRelayTransport(peer: MeshPeer, selector: TransportSelector): Boolean =
        selector.candidates(peer).any { (transport, _) ->
            canRelayOver(peer, transport.kind) && supportsTrace(peer, transport.kind)
        }
}
