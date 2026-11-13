package com.hybridmesh.relay.messaging.lora

import com.hybridmesh.relay.messaging.mesh.MeshPacket
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBridgeFramingTest {
    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    // --- chunking ---

    @Test
    fun singleChunkMatchesProtocolExample() {
        val hello = byteArrayOf(0x48, 0x45, 0x4C, 0x4C, 0x4F)
        val chunks = BleBridgeFraming.split(hello, mtu = 247)
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(0, 1, 0x48, 0x45, 0x4C, 0x4C, 0x4F), chunks[0])
    }

    @Test
    fun splitsByNegotiatedMtuWithIndexAndCount() {
        // MTU 23 -> 18 data bytes per chunk; 24 bytes -> 2 chunks (matches the bridge test packet).
        val chunks = BleBridgeFraming.split(bytes(24), mtu = 23)
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(8, chunks[1].size)
        assertEquals(listOf(0, 2), chunks[0].take(2).map { it.toInt() })
        assertEquals(listOf(1, 2), chunks[1].take(2).map { it.toInt() })
        // A full 255-byte packet at the minimum MTU still fits the 1-byte count.
        assertEquals(15, BleBridgeFraming.split(bytes(255), mtu = 23).size)
        assertEquals(2, BleBridgeFraming.split(bytes(255), mtu = 247).size)
    }

    // --- 255-byte limit ---

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsPacketsOver255Bytes() {
        BleBridgeFraming.split(bytes(256), mtu = 247)
    }

    @Test
    fun reassemblerDropsWholePacketOver255BytesNeverTruncates() {
        val r = BleBridgeReassembler()
        val chunk0 = byteArrayOf(0, 2) + bytes(200)
        val chunk1 = byteArrayOf(1, 2) + bytes(100)
        assertNull(r.accept(chunk0, 0).packet)
        val outcome = r.accept(chunk1, 10)
        assertNull(outcome.packet)
        assertTrue(outcome.dropReason!!.startsWith("PACKET_TOO_LARGE"))
    }

    @Test
    fun shortTextMeshPacketFitsInOneLoRaPacket() {
        val origin = "HMR-11111111-1111-1111-1111-111111111111"
        val messageId = "HMRM-11111111-1111-1111-1111-111111111111::00000000-0000-0000-0000-000000000001"
        val packet = MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = origin,
            destinationNodeId = "HMR-22222222-2222-2222-2222-222222222222",
            createdAt = 1_000L,
            expiresAt = 1_000L + 30 * 60 * 1000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = "NORMAL",
            content = "hello"
        )
        val encoded = MeshPacketCodec.encode(packet)
        println("encoded 'hello' DATA packet: ${encoded.size} bytes (LoRa limit 255)")
        assertTrue("encoded ${encoded.size} bytes", encoded.size <= BleBridgeConstants.MAX_PACKET_BYTES)
    }

    // --- reassembly ---

    @Test
    fun roundTripsThroughSplitAndReassembly() {
        for (mtu in listOf(23, 64, 247)) {
            val packet = bytes(255)
            val r = BleBridgeReassembler()
            val results = BleBridgeFraming.split(packet, mtu).mapIndexed { i, c -> r.accept(c, i.toLong()) }
            results.dropLast(1).forEach { assertNull(it.packet); assertNull(it.dropReason) }
            assertArrayEquals("mtu=$mtu", packet, results.last().packet)
        }
    }

    // --- framing errors (same reasons as the firmware's LORADBG DROP lines) ---

    @Test
    fun framingErrorsAreReportedAndReset() {
        val r = BleBridgeReassembler()
        assertTrue(r.accept(byteArrayOf(0, 1), 0).dropReason!!.startsWith("CHUNK_TOO_SHORT"))
        assertTrue(r.accept(byteArrayOf(0, 0, 9), 0).dropReason!!.startsWith("BAD_CHUNK_HEADER"))
        assertTrue(r.accept(byteArrayOf(2, 2, 9), 0).dropReason!!.startsWith("BAD_CHUNK_HEADER"))
        assertTrue(r.accept(byteArrayOf(1, 3, 9), 0).dropReason!!.startsWith("MISSING_FIRST_CHUNK"))

        r.accept(byteArrayOf(0, 3, 9), 0)
        assertTrue(r.accept(byteArrayOf(2, 3, 9), 1).dropReason!!.startsWith("OUT_OF_ORDER"))
        assertTrue(!r.inProgress)
    }

    @Test
    fun timeoutRestartAndDisconnectDropThePartialPacket() {
        val r = BleBridgeReassembler(timeoutMs = 2_000)
        r.accept(byteArrayOf(0, 2, 1), 0)
        assertTrue(r.accept(byteArrayOf(1, 2, 2), 2_001).dropReason!!.startsWith("REASSEMBLY_TIMEOUT"))

        r.accept(byteArrayOf(0, 2, 1), 3_000)
        val restarted = r.accept(byteArrayOf(0, 1, 7), 3_001)
        assertTrue(restarted.dropReason!!.startsWith("TRANSFER_RESTARTED"))
        assertArrayEquals(byteArrayOf(7), restarted.packet)

        r.accept(byteArrayOf(0, 2, 1), 4_000)
        assertTrue(r.onDisconnect()!!.startsWith("DISCONNECTED_MID_TRANSFER"))
        assertNull(r.onDisconnect())
    }
}
