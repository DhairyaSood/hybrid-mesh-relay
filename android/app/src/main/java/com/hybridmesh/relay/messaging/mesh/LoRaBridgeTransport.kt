package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.diagnostics.MeshDbg
import com.hybridmesh.relay.messaging.lora.BleBridgeConstants
import com.hybridmesh.relay.messaging.lora.LoRaBridgeLink
import com.hybridmesh.relay.messaging.lora.LoRaBridgeState
import com.hybridmesh.relay.messaging.lora.LoRaBridgeWriteResult

/**
 * LoRa bearer through a NEYRA-LORA ESP32 bridge (phone -BLE-> bridge -LoRa-> bridge -BLE-> phone).
 * Carries the unchanged MeshPacketCodec bytes, one mesh packet per LoRa packet, max 255 bytes.
 *
 * LoRa is a broadcast link with no per-node addressing, so the far side is represented by
 * one relay-capable gateway peer (see MeshPeerDirectory.setLoRaGateway). Only that peer is
 * reachable over this transport.
 *
 * Acceptance semantics differ from BLE/Wi-Fi: LoRa has no hop acknowledgement, so
 * [MeshTransportSendResult.Accepted] means the bridge accepted every chunk for transmission.
 * End-to-end delivery is still confirmed only by the mesh DELIVERY_ACK.
 *
 * Off by default: unavailable unless the LoRa preference is enabled and a bridge is connected.
 */
internal class LoRaBridgeTransport(
    private val link: LoRaBridgeLink,
    private val isEnabled: () -> Boolean
) : MeshTransport {
    override val kind: MeshTransportKind = MeshTransportKind.LORA

    fun readyBridgeAddress(): String? = (link.state.value as? LoRaBridgeState.Ready)?.address

    override fun isAvailable(): Boolean = isEnabled() && readyBridgeAddress() != null

    override fun canReach(peer: MeshTransportPeer): Boolean {
        val bridge = readyBridgeAddress() ?: return false
        return peer.loraBridgeAddress.equals(bridge, ignoreCase = true)
    }

    override suspend fun send(
        peer: MeshTransportPeer,
        encodedPacket: ByteArray,
        messageId: String?
    ): MeshTransportSendResult {
        val label = "msg=${messageId?.takeLast(8)} peer=${MeshDbg.node(peer.nodeId)} bytes=${encodedPacket.size}"
        if (!isEnabled()) return MeshTransportSendResult.Unavailable(kind, "LORA_DISABLED")
        val bridge = readyBridgeAddress()
            ?: return MeshTransportSendResult.Unavailable(kind, "LORA_BRIDGE_NOT_CONNECTED")
        if (!canReach(peer)) return MeshTransportSendResult.Unavailable(kind, "LORA_ENDPOINT_UNAVAILABLE")
        if (encodedPacket.size > BleBridgeConstants.MAX_PACKET_BYTES) {
            MeshDbg.log(
                "SEND",
                "lora rejected $label max=${BleBridgeConstants.MAX_PACKET_BYTES} reason=LORA_PACKET_TOO_LARGE " +
                    "(LoRa carries one packet of at most 255 bytes; this one stays on BLE/Wi-Fi)"
            )
            return MeshTransportSendResult.Failed(kind, "LORA_PACKET_TOO_LARGE")
        }
        return when (val result = link.sendPacket(encodedPacket)) {
            is LoRaBridgeWriteResult.Written -> {
                MeshDbg.log("SEND", "lora handed to bridge addr=$bridge $label chunks=${result.chunks} mtu=${result.mtu}")
                MeshTransportSendResult.Accepted(kind)
            }
            is LoRaBridgeWriteResult.Failed -> {
                MeshDbg.log("SEND", "lora failed addr=$bridge $label reason=${result.reason}")
                MeshTransportSendResult.Failed(kind, "LORA_BRIDGE_${result.reason.substringBefore(' ')}")
            }
        }
    }
}
