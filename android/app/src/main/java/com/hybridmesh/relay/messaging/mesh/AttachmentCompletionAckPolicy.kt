package com.hybridmesh.relay.messaging.mesh

/** Validates a recipient's end-to-end proof before the origin changes file state. */
internal object AttachmentCompletionAckPolicy {
    fun matches(
        packet: MeshPacket,
        messageId: String,
        localNodeId: String,
        expectedRecipientNodeId: String,
        expectedSha256: String
    ): Boolean =
        packet.packetType == MeshPacket.PacketType.ATTACHMENT_ACK &&
            packet.messageType == MeshPacketCodec.ATTACHMENT_ACK_TYPE &&
            packet.messageId == messageId &&
            packet.destinationNodeId.equals(localNodeId, true) &&
            packet.originNodeId.equals(expectedRecipientNodeId, true) &&
            expectedSha256.matches(SHA256_PATTERN) &&
            packet.content.equals(expectedSha256, true)

    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}
