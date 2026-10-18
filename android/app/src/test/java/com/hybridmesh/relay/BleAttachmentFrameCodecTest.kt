package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import org.junit.Assert.assertEquals
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
}
