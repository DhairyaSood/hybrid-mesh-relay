package com.hybridmesh.relay.messaging.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshForwardingRecordCodecTest {
    private val a = "HMR-00000000-0000-0000-0000-00000000000a"
    private val b = "HMR-00000000-0000-0000-0000-00000000000b"
    private val c = "HMR-00000000-0000-0000-0000-00000000000c"
    private val now = 1_800_000_000_000L

    /** The DATA packet exactly as MeshEngine.processOutgoingMessage builds it at the origin. */
    private fun originPacket(): MeshPacket {
        val messageId = "HMRM-${a.removePrefix("HMR-")}::0f0e0d0c-0b0a-0908-0706-050403020100"
        return MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = a,
            destinationNodeId = c,
            createdAt = now,
            expiresAt = now + 30 * 60 * 1000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = "NORMAL",
            content = "A to C via B",
            routeTransportCodes = emptyList(),
            routeTraceComplete = true
        )
    }

    /** One relay hop: decode from the wire, store as B does, reload as the forwarding worker does. */
    private fun relayThrough(wire: ByteArray, predecessor: String): MeshPacket {
        val received = requireNotNull(MeshPacketCodec.decode(wire)) { "relay could not decode packet" }
        val stored = MeshForwardingRecordCodec.toRecord(
            packet = MeshForwardingRecordCodec.withObservedIngress(received, MeshTransportKind.BLE_GATT),
            predecessor = predecessor,
            predecessorAddress = "AA:BB:CC:DD:EE:FF",
            nextAttemptAt = now
        )
        val reloaded = MeshForwardingRecordCodec.toPacket(stored)
        assertNotNull(
            "relay dropped its own stored DATA row as invalid (hop=${stored.hopCount} trace='${stored.routeTrace}')",
            reloaded
        )
        return MeshForwardingRecordCodec.outbound(reloaded!!)
    }

    @Test
    fun relayedDataRowSurvivesReloadAndReachesDestination() {
        val forwarded = relayThrough(MeshPacketCodec.encode(originPacket()), predecessor = a)

        val atDestination = MeshPacketCodec.decode(MeshPacketCodec.encode(forwarded))

        assertNotNull(atDestination)
        assertEquals(c, atDestination!!.destinationNodeId)
        assertEquals(1, atDestination.hopCount)
        assertEquals(MeshPacketCodec.INITIAL_TTL - 1, atDestination.ttl)
        assertEquals(listOf(MeshTransportKind.BLE_GATT.traceCode), atDestination.routeTransportCodes)
        assertTrue(atDestination.routeTraceComplete)
    }

    @Test
    fun twoRelayHopsKeepACompleteTrace() {
        val afterB = relayThrough(MeshPacketCodec.encode(originPacket()), predecessor = a)
        val afterSecondRelay = relayThrough(MeshPacketCodec.encode(afterB), predecessor = b)

        val atDestination = requireNotNull(MeshPacketCodec.decode(MeshPacketCodec.encode(afterSecondRelay)))

        assertEquals(2, atDestination.hopCount)
        assertEquals(2, atDestination.routeTransportCodes.size)
        assertTrue(atDestination.routeTraceComplete)
    }

    @Test
    fun deliveryAckRowIsUnchangedByIngressAndReloads() {
        val ack = MeshPacket(
            packetId = MeshPacketCodec.stableDeliveryAckPacketId(originPacket().messageId),
            messageId = originPacket().messageId,
            originNodeId = c,
            destinationNodeId = a,
            createdAt = now,
            expiresAt = now + 30 * 60 * 1000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DELIVERY_ACK,
            messageType = "DELIVERY_ACK",
            content = "",
            deliveryHopCount = 1,
            routeTransportCodes = listOf(1, 1),
            routeTraceComplete = true
        )

        val observed = MeshForwardingRecordCodec.withObservedIngress(ack, MeshTransportKind.BLE_GATT)
        val reloaded = MeshForwardingRecordCodec.toPacket(
            MeshForwardingRecordCodec.toRecord(observed, c, null, now)
        )

        assertEquals(ack, observed)
        assertEquals(ack, reloaded)
    }
}
