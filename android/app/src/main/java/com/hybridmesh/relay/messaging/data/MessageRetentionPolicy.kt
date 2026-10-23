package com.hybridmesh.relay.messaging.data

/** Admission limits for remote state that would otherwise grow for the app's lifetime. */
internal object MessageRetentionPolicy {
    // Automatically prune only remote conversation history after one month.
    // Local-origin records and unfinished delivery/transfer states are retained.
    const val REMOTE_HISTORY_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    const val REMOTE_HISTORY_PURGE_BATCH_SIZE = 64
    const val MAX_PERSISTED_PEERS = 2_000
    const val MAX_REMOTE_MESSAGES = 5_000
    const val MAX_REMOTE_MESSAGES_PER_SENDER = 500
    const val MAX_REMOTE_MESSAGE_BYTES = 32L * 1024 * 1024
    const val MAX_REMOTE_ATTACHMENTS = 2_000
    const val MAX_REMOTE_ATTACHMENT_BYTES = 256L * 1024 * 1024

    fun shouldPruneRemoteMessage(
        senderNodeId: String,
        localNodeId: String,
        status: String,
        createdAt: Long,
        cutoff: Long,
        hasAttachment: Boolean,
        hasPendingForwarding: Boolean
    ): Boolean =
        !senderNodeId.equals(localNodeId, true) &&
            status in setOf("DELIVERED", "FAILED") &&
            createdAt < cutoff &&
            !hasAttachment &&
            !hasPendingForwarding

    fun shouldPruneRemoteAttachment(
        senderNodeId: String,
        localNodeId: String,
        status: String,
        updatedAt: Long,
        cutoff: Long,
        hasPendingCompletionAck: Boolean
    ): Boolean =
        !senderNodeId.equals(localNodeId, true) &&
            status in setOf("DELIVERED", "RELAYED", "FAILED") &&
            updatedAt < cutoff &&
            !hasPendingCompletionAck

    fun allowsIncomingMessage(
        retainedMessages: Int,
        retainedMessagesFromSender: Int,
        retainedBytes: Long,
        incomingBytes: Int
    ): Boolean =
        retainedMessages in 0 until MAX_REMOTE_MESSAGES &&
            retainedMessagesFromSender in 0 until MAX_REMOTE_MESSAGES_PER_SENDER &&
            retainedBytes >= 0L && incomingBytes >= 0 &&
            incomingBytes.toLong() <= MAX_REMOTE_MESSAGE_BYTES - retainedBytes

    fun allowsIncomingAttachment(
        retainedAttachments: Int,
        retainedBytes: Long,
        incomingBytes: Long
    ): Boolean =
        retainedAttachments in 0 until MAX_REMOTE_ATTACHMENTS &&
            retainedBytes >= 0L && incomingBytes > 0L &&
            incomingBytes <= MAX_REMOTE_ATTACHMENT_BYTES - retainedBytes
}
