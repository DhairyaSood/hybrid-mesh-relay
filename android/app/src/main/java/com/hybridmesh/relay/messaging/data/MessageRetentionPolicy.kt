package com.hybridmesh.relay.messaging.data

/** Admission limits for remote state that would otherwise grow for the app's lifetime. */
internal object MessageRetentionPolicy {
    const val MAX_PERSISTED_PEERS = 2_000
    const val MAX_REMOTE_MESSAGES = 5_000
    const val MAX_REMOTE_MESSAGES_PER_SENDER = 500
    const val MAX_REMOTE_MESSAGE_BYTES = 32L * 1024 * 1024
    const val MAX_REMOTE_ATTACHMENTS = 2_000
    const val MAX_REMOTE_ATTACHMENT_BYTES = 256L * 1024 * 1024

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
