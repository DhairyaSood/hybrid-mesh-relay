package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.data.MessageRetentionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRetentionPolicyTest {
    private val local = "HMR-11111111-1111-1111-1111-111111111111"
    private val remote = "HMR-22222222-2222-2222-2222-222222222222"
    private val cutoff = 1_000_000L

    @Test
    fun onlyOldSettledRemoteMessagesWithoutActiveDependentsCanBePruned() {
        assertTrue(messageEligible())
        assertFalse(messageEligible(sender = local))
        assertFalse(messageEligible(status = "IN_FLIGHT"))
        assertFalse(messageEligible(createdAt = cutoff))
        assertFalse(messageEligible(hasAttachment = true))
        assertFalse(messageEligible(hasPendingForwarding = true))
    }

    @Test
    fun activeAttachmentsAndPendingCompletionAcksArePreserved() {
        assertTrue(attachmentEligible(status = "RELAYED"))
        assertFalse(attachmentEligible(sender = local))
        assertFalse(attachmentEligible(status = "RECEIVING"))
        assertFalse(attachmentEligible(updatedAt = cutoff))
        assertFalse(attachmentEligible(hasPendingAck = true))
    }

    @Test
    fun localUserHistoryIsNeverAutomaticallyPruned() {
        assertFalse(messageEligible(sender = local, status = "DELIVERED"))
        assertFalse(attachmentEligible(sender = local, status = "DELIVERED"))
    }

    private fun messageEligible(
        sender: String = remote,
        status: String = "DELIVERED",
        createdAt: Long = cutoff - 1,
        hasAttachment: Boolean = false,
        hasPendingForwarding: Boolean = false
    ) = MessageRetentionPolicy.shouldPruneRemoteMessage(
        senderNodeId = sender,
        localNodeId = local,
        status = status,
        createdAt = createdAt,
        cutoff = cutoff,
        hasAttachment = hasAttachment,
        hasPendingForwarding = hasPendingForwarding
    )

    private fun attachmentEligible(
        sender: String = remote,
        status: String = "DELIVERED",
        updatedAt: Long = cutoff - 1,
        hasPendingAck: Boolean = false
    ) = MessageRetentionPolicy.shouldPruneRemoteAttachment(
        senderNodeId = sender,
        localNodeId = local,
        status = status,
        updatedAt = updatedAt,
        cutoff = cutoff,
        hasPendingCompletionAck = hasPendingAck
    )
}
