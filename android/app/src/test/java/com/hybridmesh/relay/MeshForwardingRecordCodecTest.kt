package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.mesh.MeshForwardingRecordCodec
import com.hybridmesh.relay.messaging.mesh.MeshPacket
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshForwardingRecordCodecTest {
    @Test
    fun completeBleIngressTraceDoesNotInvalidatePendingDataRelay() {
        val messageId = "HMRM-11111111-1111-1111-1111-111111111111::0000000000000001"
        val record = MeshForwardingRecordEntity(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = "HMR-11111111-1111-1111-1111-111111111111",
            destinationNodeId = "HMR-33333333-3333-3333-3333-333333333333",
            receivedFromNodeId = "HMR-22222222-2222-2222-2222-222222222222",
            receivedFromAddress = "02:00:00:00:00:02",
            createdAt = 1_000L,
            expiresAt = 100_000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA.code.toInt(),
            messageType = "NORMAL",
            content = "relay this packet",
            deliveryHopCount = null,
            state = "PENDING",
            attemptCount = 0,
            nextAttemptAt = 1_100L,
            lastError = null,
            routeTrace = "1",
            routeTraceComplete = true
        )

        val pendingPacket = MeshForwardingRecordCodec.toPacket(record)
        assertNotNull("complete ingress trace must survive relay-row decoding", pendingPacket)
        val packet = requireNotNull(pendingPacket)

        val outboundPacket = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1
        )
        val encoded = MeshPacketCodec.encode(outboundPacket)
        assertEquals(outboundPacket, MeshPacketCodec.decode(encoded))
        assertEquals(listOf(1), outboundPacket.routeTransportCodes)
    }
}
