package com.hybridmesh.relay

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hybridmesh.relay.messaging.data.AttachmentRecordEntity
import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MessagingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagingRetentionDaoInstrumentedTest {
    private val localId = "HMR-11111111-1111-1111-1111-111111111111"
    private val remoteId = "HMR-22222222-2222-2222-2222-222222222222"
    private val cutoff = 1_000_000L
    private lateinit var database: MessagingDatabase

    @Before
    fun createDatabase() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MessagingDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun metadataOnlyRelayAttachmentsDoNotConsumePayloadQuota() = runBlocking {
        val attachments = database.attachmentDao()
        attachments.upsert(attachment("delivered", "DELIVERED", localPath = "/attachments/delivered.media", sizeBytes = 100L))
        attachments.upsert(attachment("relayed", "RELAYED", localPath = null, sizeBytes = 200L))
        attachments.upsert(attachment("relayed-cleanup-pending", "RELAYED", localPath = "/attachments/still-present.media", sizeBytes = 50L))
        attachments.upsert(attachment("receiving", "RECEIVING", localPath = null, sizeBytes = 300L))

        assertEquals(3, attachments.countRemoteAttachments(localId, "incoming"))
        assertEquals(450L, attachments.countRetainedRemoteAttachmentBytes(localId, "incoming"))
    }

    @Test
    fun retentionQueriesPreserveActiveTransfersMessagesAndPendingAttachmentAcks() = runBlocking {
        val attachments = database.attachmentDao()
        val messages = database.messageDao()
        val forwarding = database.meshForwardingDao()

        attachments.upsert(attachment("old-delivered", "DELIVERED", localPath = "/attachments/old.media", updatedAt = cutoff - 1))
        attachments.upsert(attachment("old-relayed", "RELAYED", localPath = null, updatedAt = cutoff - 1))
        attachments.upsert(attachment("old-receiving", "RECEIVING", localPath = null, updatedAt = cutoff - 1))
        attachments.upsert(attachment("old-ack-pending", "DELIVERED", localPath = "/attachments/ack.media", updatedAt = cutoff - 1))
        forwarding.insert(forwardingRecord("old-ack-pending", packetType = 3, state = "PENDING"))

        messages.insert(message("old-plain", senderId = remoteId, createdAt = cutoff - 1))
        messages.insert(message("old-with-attachment", senderId = remoteId, createdAt = cutoff - 1))
        messages.insert(message("old-local", senderId = localId, createdAt = cutoff - 1))
        messages.insert(message("old-with-forward", senderId = remoteId, createdAt = cutoff - 1))
        attachments.upsert(attachment("old-with-attachment", "RECEIVING", localPath = null, updatedAt = cutoff - 1))
        forwarding.insert(forwardingRecord("old-with-forward", packetType = 1, state = "FORWARDING"))

        val expiredAttachments = attachments.getExpiredRemoteHistory(localId, cutoff, 16)
        assertEquals(setOf("old-delivered", "old-relayed"), expiredAttachments.map { it.messageId }.toSet())

        val expiredMessages = messages.getExpiredRemoteHistory(localId, cutoff, 16)
        assertEquals(setOf("old-plain"), expiredMessages.map { it.messageId }.toSet())
        assertTrue(expiredMessages.none { it.senderNodeId.equals(localId, true) })

        val attachmentRowsRemoved = expiredAttachments.sumOf {
            attachments.deleteExpiredRemoteHistory(it.messageId, localId, cutoff)
        }
        assertEquals(2, attachmentRowsRemoved)
        assertEquals(3, attachments.countRemoteAttachments(localId, "incoming"))

        expiredMessages.forEach { messages.deleteExpiredRemoteHistory(it.messageId, localId, cutoff) }
        assertEquals(2, messages.countRemoteMessages(localId))
    }

    @Test
    fun startupRecoveryPreservesTransferAgeAndExpiresStaleWaitingTransfer() = runBlocking {
        val attachments = database.attachmentDao()
        val staleUpdatedAt = cutoff - 10_000L
        attachments.upsert(
            attachment(
                "stale-receiving",
                "RECEIVING",
                localPath = null,
                sizeBytes = 400L,
                updatedAt = staleUpdatedAt
            )
        )
        attachments.upsert(
            attachment(
                "active-receiving",
                "RECEIVING",
                localPath = null,
                sizeBytes = 500L,
                updatedAt = cutoff + 1L
            )
        )
        attachments.upsert(
            attachment(
                "active-waiting",
                "WAITING_WIFI",
                localPath = null,
                sizeBytes = 600L,
                updatedAt = cutoff + 1L
            )
        )

        attachments.resetInterruptedTransfers()
        assertEquals(staleUpdatedAt, attachments.get("HMRM-$remoteId::stale-receiving")!!.updatedAt)
        assertTrue(
            "HMRM-$remoteId::active-waiting" in
                attachments.getActiveIncomingTransferMessageIds(localId)
        )
        assertEquals(
            setOf("HMRM-$remoteId::stale-receiving"),
            attachments.getStaleIncomingTransfers(localId, cutoff, 16).map { it.messageId }.toSet()
        )

        assertEquals(
            1,
            attachments.markStaleIncomingTransfer(
                messageId = "HMRM-$remoteId::stale-receiving",
                localNodeId = localId,
                cutoff = cutoff,
                now = cutoff,
                lastError = "TRANSFER_EXPIRED"
            )
        )
        assertEquals("FAILED", attachments.get("HMRM-$remoteId::stale-receiving")!!.status)
        assertEquals(2, attachments.countRemoteAttachments(localId, "incoming"))
        assertEquals(1_100L, attachments.countRetainedRemoteAttachmentBytes(localId, "incoming"))
        assertTrue(
            attachments.getStaleIncomingTransfers(localId, cutoff, 16)
                .none { it.messageId == "HMRM-$remoteId::active-receiving" }
        )
    }

    @Test
    fun lostDeliveryAckDuplicateRequeuesForwardedDataButKeepsSingleMessageRow() = runBlocking {
        val forwarding = database.meshForwardingDao()
        val messages = database.messageDao()
        val messageId = "HMRM-$remoteId::lost-ack"
        val packetId = "HMR-55555555-5555-5555-5555-555555555555"
        val forwardedAt = 100_000L
        forwarding.insert(
            forwardingRecord(
                id = "lost-ack",
                packetType = 1,
                state = "FORWARDED",
                packetId = packetId,
                messageId = messageId,
                attemptCount = 1,
                nextAttemptAt = forwardedAt
            )
        )

        assertEquals(0, forwarding.requeueForwardedDataAfterRetry(packetId, 110_000L, 90_000L, 8))
        assertEquals(1, forwarding.requeueForwardedDataAfterRetry(packetId, 121_000L, 101_000L, 8))
        assertEquals("PENDING", forwarding.get(packetId)!!.state)

        assertEquals(1L, messages.insertIncoming(message("lost-ack", senderId = remoteId, createdAt = cutoff)))
        assertEquals(-1L, messages.insertIncoming(message("lost-ack", senderId = remoteId, createdAt = cutoff)))
        assertEquals(1, messages.countRemoteMessages(localId))

        assertEquals(1, forwarding.markDataDelivered(messageId, remoteId, localId))
        assertEquals("DELIVERED", forwarding.get(packetId)!!.state)
        assertEquals(0, forwarding.requeueForwardedDataAfterRetry(packetId, 150_000L, 130_000L, 8))
        assertEquals(1, messages.countRemoteMessages(localId))
    }

    @Test
    fun staleAttachmentUpdatesCannotReplaceDeliveredState() = runBlocking {
        val attachments = database.attachmentDao()
        val messageId = "HMRM-$localId::attachment-state-race"
        attachments.upsert(
            attachment("attachment-state-race", "RECEIVING", "/attachments/race.media")
                .copy(messageId = messageId, senderNodeId = localId, recipientNodeId = remoteId)
        )

        assertEquals(1, attachments.markDeliveredByRecipient(messageId, localId, cutoff))
        assertEquals(
            0,
            attachments.updateProgress(messageId, "SENDING", 4L, cutoff + 1L, "stale-progress")
        )
        assertEquals(
            0,
            attachments.updateStatusAndPath(messageId, "FAILED", null, cutoff + 2L, "stale-expiry")
        )
        assertEquals("DELIVERED", attachments.get(messageId)!!.status)
    }

    @Test
    fun retrySchedulingCannotRequeueDeliveredOrRemoteMessages() = runBlocking {
        val messages = database.messageDao()
        val deliveredLocal = message("retry-delivered-local", localId, cutoff, status = "DELIVERED")
        val deliveredRemote = message("retry-delivered-remote", remoteId, cutoff, status = "DELIVERED")
        val queuedLocal = message("retry-queued-local", localId, cutoff, status = "QUEUED")
        messages.insert(deliveredLocal)
        messages.insert(deliveredRemote)
        messages.insert(queuedLocal)

        assertEquals(0, messages.scheduleRetry(
            deliveredLocal.messageId, localId, "BLE", 3, cutoff + 1_000L, "stale-retry"
        ))
        assertEquals(0, messages.scheduleRetry(
            deliveredRemote.messageId, localId, "BLE", 3, cutoff + 1_000L, "wrong-owner"
        ))
        assertEquals(1, messages.scheduleRetry(
            queuedLocal.messageId, localId, "BLE", 3, cutoff + 1_000L, "retryable"
        ))

        assertEquals("DELIVERED", messages.getById(deliveredLocal.messageId)!!.status)
        assertEquals("DELIVERED", messages.getById(deliveredRemote.messageId)!!.status)
        assertEquals("QUEUED", messages.getById(queuedLocal.messageId)!!.status)
        assertEquals("retryable", messages.getById(queuedLocal.messageId)!!.lastError)
    }

    private fun attachment(
        id: String,
        status: String,
        localPath: String?,
        sizeBytes: Long = 10L,
        updatedAt: Long = cutoff - 100L
    ) = AttachmentRecordEntity(
        messageId = "HMRM-$remoteId::$id",
        senderNodeId = remoteId,
        recipientNodeId = localId,
        mimeType = "image/png",
        displayName = "$id.png",
        sizeBytes = sizeBytes,
        sha256 = "a".repeat(64),
        localPath = localPath,
        status = status,
        receivedFromNodeId = remoteId,
        hopCount = 1,
        createdAt = cutoff - 200L,
        transferredBytes = if (status == "DELIVERED") sizeBytes else 0L,
        updatedAt = updatedAt
    )

    private fun message(
        id: String,
        senderId: String,
        createdAt: Long,
        status: String = "DELIVERED"
    ) = MessageRecordEntity(
        messageId = "HMRM-$remoteId::$id",
        senderNodeId = senderId,
        recipientNodeId = localId,
        content = "text",
        createdAt = createdAt,
        messageType = "NORMAL",
        status = status,
        lastTransport = "BLE_MESH",
        deliveredAt = createdAt,
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        deliveryHopCount = 1
    )

    private fun forwardingRecord(
        id: String,
        packetType: Int,
        state: String,
        packetId: String = "HMR-33333333-3333-3333-3333-333333333333-$id",
        messageId: String = "HMRM-$remoteId::$id",
        attemptCount: Int = 0,
        nextAttemptAt: Long? = null
    ) = MeshForwardingRecordEntity(
        packetId = packetId,
        messageId = messageId,
        originNodeId = remoteId,
        destinationNodeId = localId,
        receivedFromNodeId = remoteId,
        receivedFromAddress = null,
        createdAt = cutoff - 200L,
        expiresAt = cutoff + 1_000L,
        ttl = 7,
        hopCount = 1,
        packetType = packetType,
        messageType = if (packetType == 3) "ATTACHMENT_ACK" else "NORMAL",
        content = if (packetType == 3) "a".repeat(64) else "text",
        deliveryHopCount = null,
        state = state,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = null
    )
}
