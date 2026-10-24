package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.MeshPacket
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshPacketCodecBoundaryTest {
    private val origin = "HMR-11111111-1111-1111-1111-111111111111"
    private val destination = "HMR-22222222-2222-2222-2222-222222222222"

    @Test
    fun decoderRejectsDataPacketIdThatDoesNotMatchStableMessageId() {
        val bytes = MeshPacketCodec.encode(dataPacket(content = "valid"))
        val packetUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            position(PACKET_UUID_OFFSET)
            putLong(packetUuid.mostSignificantBits)
            putLong(packetUuid.leastSignificantBits)
        }
        refreshCrc(bytes)

        assertNull(MeshPacketCodec.decode(bytes))
    }

    @Test
    fun encoderRejectsPacketIdThatDoesNotMatchStableMessageId() {
        val malformed = dataPacket(content = "valid").copy(
            packetId = "HMR-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )

        assertFalse(runCatching { MeshPacketCodec.encode(malformed) }.isSuccess)
    }

    @Test
    fun decoderRejectsMalformedUtf8InsteadOfChangingMessageContent() {
        val bytes = MeshPacketCodec.encode(dataPacket(content = "A"))
        bytes[dataContentOffset(bytes)] = 0xC3.toByte()
        refreshCrc(bytes)

        assertNull(MeshPacketCodec.decode(bytes))
    }

    @Test
    fun encoderRejectsMalformedUtf16InsteadOfReplacingItOnTheWire() {
        assertFalse(runCatching {
            MeshPacketCodec.encode(dataPacket(content = "invalid\uD800text"))
        }.isSuccess)
    }

    @Test
    fun decoderRejectsEmptyTruncatedOversizedAndBadCrcPackets() {
        assertNull(MeshPacketCodec.decode(byteArrayOf()))

        val valid = MeshPacketCodec.encode(dataPacket(content = ""))
        assertNull(MeshPacketCodec.decode(valid.copyOf(valid.size - 1)))
        assertNull(MeshPacketCodec.decode(ByteArray(MeshPacketCodec.MAX_PACKET_BYTES + 1)))

        val badCrc = valid.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        assertNull(MeshPacketCodec.decode(badCrc))
    }

    @Test
    fun minimumExpiryWindowAndMaximumDataHopCountAreLegal() {
        val base = dataPacket(content = "boundary")
        val minimumWindow = base.copy(expiresAt = base.createdAt + 1L)
        assertEquals(minimumWindow, MeshPacketCodec.decode(MeshPacketCodec.encode(minimumWindow)))

        val maximumRoute = base.copy(
            ttl = 0,
            hopCount = MeshPacketCodec.INITIAL_TTL,
            routeTransportCodes = List(MeshPacketCodec.INITIAL_TTL) { (it % 3) + 1 },
            routeTraceComplete = true
        )
        assertEquals(maximumRoute, MeshPacketCodec.decode(MeshPacketCodec.encode(maximumRoute)))
    }

    @Test
    fun emptyAndMaximumUtf8DataPayloadsRoundTripExactly() {
        val empty = dataPacket(content = "")
        assertEquals(empty, MeshPacketCodec.decode(MeshPacketCodec.encode(empty)))

        val maxContent = "é".repeat(MeshPacketCodec.MAX_TEXT_BYTES / 2)
        val maximum = dataPacket(content = maxContent)
        val decoded = MeshPacketCodec.decode(MeshPacketCodec.encode(maximum))
        assertNotNull(decoded)
        assertEquals(maxContent, decoded?.content)
    }

    private fun dataPacket(content: String): MeshPacket {
        val messageId = "HMRM-${origin.removePrefix("HMR-")}::boundary"
        return MeshPacket(
            packetId = MeshPacketCodec.stableDataPacketId(messageId),
            messageId = messageId,
            originNodeId = origin,
            destinationNodeId = destination,
            createdAt = 1_000L,
            expiresAt = 1_000L + 30 * 60 * 1_000L,
            ttl = MeshPacketCodec.INITIAL_TTL,
            hopCount = 0,
            packetType = MeshPacket.PacketType.DATA,
            messageType = "NORMAL",
            content = content,
            routeTraceComplete = true
        )
    }

    private fun dataContentOffset(bytes: ByteArray): Int {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        header.position(MESSAGE_ID_LENGTH_OFFSET)
        val messageIdLength = header.short.toInt() and 0xFFFF
        val messageTypeLength = header.short.toInt() and 0xFFFF
        val routeCount = bytes[ROUTE_COUNT_OFFSET].toInt() and 0xFF
        return ROUTE_COUNT_OFFSET + 2 + routeCount + messageIdLength + messageTypeLength
    }

    private fun refreshCrc(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - CRC_BYTES) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size - CRC_BYTES, crc.toInt())
    }

    private companion object {
        const val PACKET_UUID_OFFSET = 4
        const val MESSAGE_ID_LENGTH_OFFSET = 52
        const val ROUTE_COUNT_OFFSET = 77
        const val CRC_BYTES = 4
    }
}
