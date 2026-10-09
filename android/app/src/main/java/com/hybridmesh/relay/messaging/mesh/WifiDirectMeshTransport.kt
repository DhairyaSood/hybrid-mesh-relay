package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.wifi.WifiDirectManager

/** Wi-Fi Direct adapter over the existing mesh packet codec and ACK semantics. */
internal class WifiDirectMeshTransport(
    private val wifiDirectManager: WifiDirectManager
) : MeshTransport {
    override val kind: MeshTransportKind = MeshTransportKind.WIFI_DIRECT

    override fun isAvailable(): Boolean = wifiDirectManager.isAvailable()

    override fun canReach(peer: MeshTransportPeer): Boolean = wifiDirectManager.hasPeer(peer.nodeId)

    override suspend fun send(
        peer: MeshTransportPeer,
        encodedPacket: ByteArray,
        messageId: String?
    ): MeshTransportSendResult {
        if (!isAvailable()) return MeshTransportSendResult.Unavailable(kind, "WIFI_DIRECT_SESSION_UNAVAILABLE")
        if (!canReach(peer)) return MeshTransportSendResult.Unavailable(kind, "WIFI_DIRECT_PEER_UNAVAILABLE")
        return if (wifiDirectManager.sendToPeer(peer.nodeId, encodedPacket)) {
            MeshTransportSendResult.Accepted(kind)
        } else {
            MeshTransportSendResult.Failed(kind, "WIFI_DIRECT_TRANSPORT_ACK_FAILED")
        }
    }
}
