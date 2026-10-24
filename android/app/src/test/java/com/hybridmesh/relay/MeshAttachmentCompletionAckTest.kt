package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.AttachmentCompletionAckPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPacket
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAttachmentCompletionAckTest {
    private val sender = "HMR-11111111-1111-1111-1111-111111111111"
    private val recipient = "HMR-22222222-2222-2222-2222-222222222222"
    private val messageId = "HMRM-11111111-1111-1111-1111-111111111111::attachment-1"
    private val sha256 = "a".repeat(64)

    @Test
    fun completionAckCarriesVerifiedHashAndRoundTrips() {
        val packet = ack()

        val decoded = MeshPacketCodec.decode(MeshPacketCodec.encode(packet))

        assertNotNull(decoded)
        assertEquals(MeshPacket.PacketType.ATTACHMENT_ACK, decoded!!.packetType)
        assertEquals(sha256, decoded.content)
        assertEquals(messageId, decoded.messageId)
        assertTrue(
            AttachmentCompletionAckPolicy.matches(
                decoded,
                messageId,
                sender,
                recipient,
                sha256
            )
        )
    }

    @Test
    fun completionAckCannotBeAppliedForWrongSenderRecipientOrHash() {
        val packet = ack()

        assertFalse(
            AttachmentCompletionAckPolicy.matches(packet, messageId, sender, recipient, "b".repeat(64))
        )
        assertFalse(
            AttachmentCompletionAckPolicy.matches(packet, messageId, recipient, recipient, sha256)
        )
        assertFalse(
            AttachmentCompletionAckPolicy.matches(packet, messageId, sender, sender, sha256)
        )
    }

    @Test
    fun eachAttachmentHasStableCompletionAckIdentity() {
        val first = MeshPacketCodec.stableAttachmentAckPacketId(messageId)

        assertEquals(first, MeshPacketCodec.stableAttachmentAckPacketId(messageId))
        assertNotEquals(first, MeshPacketCodec.stableAttachmentAckPacketId("$messageId-other"))
        assertNotEquals(first, MeshPacketCodec.stableDeliveryAckPacketId(messageId))
    }

    @Test
    fun malformedCompletionHashIsRejectedByCodec() {
        val malformed = ack().copy(content = "not-a-hash")

        assertFalse(runCatching { MeshPacketCodec.encode(malformed) }.isSuccess)
    }

    @Test
    fun completionAckIsNotSentUsingLegacyMeshProtocol() {
        assertFalse(runCatching { MeshPacketCodec.encode(ack(), wireVersionNumber = 1) }.isSuccess)
    }

    private fun ack() = MeshPacket(
        packetId = MeshPacketCodec.stableAttachmentAckPacketId(messageId),
        messageId = messageId,
        originNodeId = recipient,
        destinationNodeId = sender,
        createdAt = 1_000L,
        expiresAt = 1_000L + 30 * 60 * 1_000L,
        ttl = MeshPacketCodec.INITIAL_TTL,
        hopCount = 0,
        packetType = MeshPacket.PacketType.ATTACHMENT_ACK,
        messageType = MeshPacketCodec.ATTACHMENT_ACK_TYPE,
        content = sha256
    )
}
