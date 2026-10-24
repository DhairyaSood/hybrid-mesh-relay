package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BleAttachmentFrameCodecTest {
    private val originNodeId = "HMR-11111111-1111-1111-1111-111111111111"
    private val relayNodeId = "HMR-22222222-2222-2222-2222-222222222222"
    private val recipientNodeId = "HMR-33333333-3333-3333-3333-333333333333"

    private fun manifest(hopCount: Int = 1) = AttachmentTransferManifest(
        messageId = "origin::attachment-1",
        senderNodeId = originNodeId,
        recipientNodeId = recipientNodeId,
        mimeType = "image/jpeg",
        displayName = "photo.jpg",
        sizeBytes = 4,
        sha256 = "a".repeat(64),
        createdAt = 1_800_000_000_000L,
        hopCount = hopCount
    )

    @Test
    fun legacyBeginRemainsDecodableWithoutHopIdentity() {
        val encoded = BleAttachmentFrameCodec.encodeBegin(77L, manifest())

        val decoded = BleAttachmentFrameCodec.decode(encoded) as BleAttachmentFrameCodec.Frame.Begin

        assertEquals(BleAttachmentFrameCodec.LEGACY_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(originNodeId, decoded.manifest.senderNodeId)
        assertNull(decoded.immediateHopSenderNodeId)
    }

    @Test
    fun v2RelayBeginKeepsOriginAndCarriesImmediateHopIdentity() {
        val encoded = BleAttachmentFrameCodec.encodeBegin(
            transferId = 78L,
            manifest = manifest(hopCount = 2),
            protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION,
            immediateHopSenderNodeId = relayNodeId
        )

        val decoded = BleAttachmentFrameCodec.decode(encoded) as BleAttachmentFrameCodec.Frame.Begin

        assertEquals(BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(originNodeId, decoded.manifest.senderNodeId)
        assertEquals(2, decoded.manifest.hopCount)
        assertEquals(relayNodeId, decoded.immediateHopSenderNodeId)
    }

    @Test
    fun chunksAndFinishUseTheNegotiatedFrameVersion() {
        val chunk = BleAttachmentFrameCodec.decode(
            BleAttachmentFrameCodec.encodeChunk(
                transferId = 79L,
                messageId = "origin::attachment-1",
                offset = 0L,
                bytes = byteArrayOf(1, 2, 3, 4),
                protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION
            )
        ) as BleAttachmentFrameCodec.Frame.Chunk
        val finish = BleAttachmentFrameCodec.decode(
            BleAttachmentFrameCodec.encodeFinish(
                transferId = 79L,
                messageId = "origin::attachment-1",
                protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION
            )
        ) as BleAttachmentFrameCodec.Frame.Finish

        assertEquals(BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION, chunk.protocolVersion)
        assertEquals(BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION, finish.protocolVersion)
        assertEquals(0L, chunk.offset)
        assertEquals(4, chunk.bytes.size)
    }

    @Test
    fun legacyBeginRejectsUnexpectedExtensionBytes() {
        val encoded = BleAttachmentFrameCodec.encodeBegin(80L, manifest()) + byteArrayOf(1)

        assertNull(BleAttachmentFrameCodec.decode(encoded))
    }

    @Test
    fun beginRejectsMalformedUtf8DisplayNameInsteadOfPersistingReplacementText() {
        val encoded = BleAttachmentFrameCodec.encodeBegin(81L, manifest()).copyOf()
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        var fieldOffset = BEGIN_HEADER_BYTES
        repeat(4) {
            val length = buffer.getShort(fieldOffset).toInt() and 0xFFFF
            fieldOffset += SHORT_BYTES + length
        }
        encoded[fieldOffset + SHORT_BYTES] = 0xC3.toByte()

        assertNull(BleAttachmentFrameCodec.decode(encoded))
    }

    @Test
    fun chunkRejectsMalformedUtf8MessageIdBeforeItCanReachTransferState() {
        val encoded = BleAttachmentFrameCodec.encodeChunk(
            80L, "\uFFFD", 0L, byteArrayOf(7)
        ).copyOf()
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(CHUNK_ID_LENGTH_OFFSET, 1)
        encoded[CHUNK_ID_OFFSET] = 0xC3.toByte()

        assertNull(BleAttachmentFrameCodec.decode(encoded))
    }

    @Test
    fun beginEncoderRejectsMalformedUtf16DisplayNameInsteadOfReplacingIt() {
        val malformed = manifest().copy(displayName = "invalid\uD800name.jpg")

        assertFalse(runCatching { BleAttachmentFrameCodec.encodeBegin(82L, malformed) }.isSuccess)
    }

    @Test
    fun chunkMessageIdLimitIsMeasuredInUtf8Bytes() {
        val maximum = "é".repeat(80)
        val maximumFrame = BleAttachmentFrameCodec.encodeChunk(83L, maximum, 0L, byteArrayOf(1))
        assertNotNull(BleAttachmentFrameCodec.decode(maximumFrame))

        val oversizedFrame = runCatching {
            BleAttachmentFrameCodec.encodeChunk(84L, "${maximum}é", 0L, byteArrayOf(1))
        }
        assertFalse(oversizedFrame.isSuccess)
    }

    @Test
    fun maximumChunkPayloadPreservesArbitraryBinaryBytes() {
        val payload = ByteArray(BleAttachmentFrameCodec.MAX_CHUNK_BYTES) { it.toByte() }
        val frame = BleAttachmentFrameCodec.encodeChunk(85L, "attachment-id", 0L, payload)
        val decoded = BleAttachmentFrameCodec.decode(frame) as BleAttachmentFrameCodec.Frame.Chunk

        assertArrayEquals(payload, decoded.bytes)
        assertFalse(runCatching {
            BleAttachmentFrameCodec.encodeChunk(
                86L, "attachment-id", 0L, ByteArray(BleAttachmentFrameCodec.MAX_CHUNK_BYTES + 1)
            )
        }.isSuccess)
    }

    private companion object {
        const val BEGIN_HEADER_BYTES = 4 + 1 + 1 + Long.SIZE_BYTES
        const val CHUNK_ID_LENGTH_OFFSET = 4 + 1 + 1 + Long.SIZE_BYTES + Long.SIZE_BYTES
        const val CHUNK_ID_OFFSET = CHUNK_ID_LENGTH_OFFSET + Short.SIZE_BYTES
        const val SHORT_BYTES = Short.SIZE_BYTES
    }
}
