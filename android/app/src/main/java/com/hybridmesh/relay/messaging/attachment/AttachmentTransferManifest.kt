package com.hybridmesh.relay.messaging.attachment

import com.hybridmesh.relay.data.NodeIdGenerator

data class AttachmentTransferManifest(
    val messageId: String,
    val senderNodeId: String,
    val recipientNodeId: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
    val hopCount: Int = 0
) {
    fun isValid(): Boolean =
        messageId.isNotBlank() && messageId.length <= 160 &&
            NodeIdGenerator.isValid(senderNodeId) && NodeIdGenerator.isValid(recipientNodeId) &&
            mimeType.let { it.startsWith("image/") || it.startsWith("video/") } &&
            displayName.isNotBlank() && displayName.length <= 180 &&
            sizeBytes in 1..AttachmentDescriptor.MAX_ATTACHMENT_BYTES &&
            sha256.matches(Regex("[0-9a-fA-F]{64}")) && createdAt > 0L && hopCount in 0..MAX_HOPS

    companion object {
        const val MAX_HOPS = 8
    }
}
