package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.protocol.GattPacketCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattPacketCodecBoundsTest {
    @Test
    fun decoderRejectsEmptyDataChunk() {
        val emptyFrame = ByteBuffer.allocate(GattPacketCodec.DATA_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(0x484D.toShort())
                put(1)
                put(1)
                putLong(1L)
                putShort(0)
                putShort(1)
            }
            .array()

        assertNull(GattPacketCodec.decode(emptyFrame))
    }

    @Test
    fun dataEncoderRejectsEmptyChunkAndLimitsChunkCount() {
        assertTrue(GattPacketCodec.MAX_CHUNKS < 65_535)
        runCatching {
            GattPacketCodec.encodeData(
                GattPacketCodec.DataFrame(
                    transferId = 1L,
                    chunkIndex = 0,
                    chunkCount = 1,
                    payload = byteArrayOf()
                )
            )
        }.onSuccess { error("An empty DATA chunk must not encode") }
    }
}
