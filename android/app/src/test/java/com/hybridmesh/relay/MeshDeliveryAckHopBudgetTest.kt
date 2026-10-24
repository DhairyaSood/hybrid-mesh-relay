package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.MeshPacket
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshDeliveryAckHopBudgetTest {
    private val sender = "HMR-11111111-1111-1111-1111-111111111111"
    private val recipient = "HMR-22222222-2222-2222-2222-222222222222"
    private val messageId = "HMRM-11111111-1111-1111-1111-111111111111::max-route"

    @Test
    fun deliveryAckCanReturnAcrossTheLongestValidDataRoute() {
        val ackOrigin = MeshPacket(
            packetId = MeshPacketCodec.stableDeliveryAckPacketId(messageId),
            messageId = messageId,
            originNodeId = recipient,
            destinationNodeId = sender,
            createdAt = 1_000L,
            expiresAt = 1_000L + 30 * 60 * 1_000L,
            ttl = MeshPacketCodec.ACK_INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DELIVERY_ACK,
            messageType = "DELIVERY_ACK",
            content = "",
            deliveryHopCount = MeshPacketCodec.INITIAL_TTL,
            routeTransportCodes = List(MeshPacketCodec.INITIAL_TTL + 1) { 1 },
            routeTraceComplete = true
        )

        var packet = ackOrigin
        repeat(MeshPacketCodec.ACK_INITIAL_TTL) {
            packet = packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
            val bytes = MeshPacketCodec.encode(packet)
            packet = requireNotNull(MeshPacketCodec.decode(bytes))
        }

        assertEquals(0, packet.ttl)
        assertEquals(MeshPacketCodec.ACK_INITIAL_TTL, packet.hopCount)
        assertEquals(sender, packet.destinationNodeId)
        assertNotNull(MeshPacketCodec.decode(MeshPacketCodec.encode(packet)))
    }
}
