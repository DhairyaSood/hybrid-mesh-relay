package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM mesh_messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MessageRecordEntity>>

    @Query("SELECT * FROM mesh_messages WHERE ((UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId))) ORDER BY createdAt ASC")
    fun observeConversation(
        localNodeId: String,
        peerNodeId: String
    ): Flow<List<MessageRecordEntity>>


    @Query("SELECT * FROM mesh_messages WHERE senderNodeId = :localNodeId AND status = 'QUEUED' ORDER BY COALESCE(nextAttemptAt, 0) ASC, createdAt ASC")
    suspend fun getQueuedOutgoing(localNodeId: String): List<MessageRecordEntity>

    @Query("SELECT * FROM mesh_messages WHERE senderNodeId = :localNodeId AND status = 'QUEUED' ORDER BY COALESCE(nextAttemptAt, 0) ASC, createdAt ASC")
    fun observeQueuedOutgoing(localNodeId: String): Flow<List<MessageRecordEntity>>

    @Query("SELECT * FROM mesh_messages WHERE senderNodeId = :localNodeId AND status = 'QUEUED' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) ORDER BY createdAt ASC")
    suspend fun getEligibleOutgoing(
        localNodeId: String,
        now: Long
    ): List<MessageRecordEntity>

    @Query("SELECT MIN(nextAttemptAt) FROM mesh_messages WHERE senderNodeId = :localNodeId AND status = 'QUEUED' AND nextAttemptAt IS NOT NULL")
    suspend fun getEarliestNextAttemptAt(localNodeId: String): Long?

    @Query("SELECT COUNT(*) FROM mesh_messages WHERE senderNodeId = :localNodeId AND status IN ('QUEUED', 'IN_FLIGHT')")
    fun observePendingCount(localNodeId: String): Flow<Int>

    @Query("SELECT * FROM mesh_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIncoming(message: MessageRecordEntity): Long

    @Query("UPDATE mesh_messages SET status = :status, lastTransport = :transport, deliveredAt = :deliveredAt, nextAttemptAt = :nextAttemptAt, lastError = :lastError, deliveryHopCount = :deliveryHopCount WHERE messageId = :messageId")
    suspend fun updateStatus(
        messageId: String,
        status: String,
        transport: String?,
        deliveredAt: Long?,
        nextAttemptAt: Long?,
        lastError: String?,
        deliveryHopCount: Int?
    )

    @Query("UPDATE mesh_messages SET status = 'DELIVERED', lastTransport = :transport, deliveredAt = :deliveredAt, nextAttemptAt = NULL, lastError = NULL, deliveryHopCount = :deliveryHopCount WHERE messageId = :messageId AND senderNodeId = :localNodeId AND status IN ('QUEUED', 'IN_FLIGHT')")
    suspend fun markDelivered(
        messageId: String,
        localNodeId: String,
        transport: String,
        deliveredAt: Long,
        deliveryHopCount: Int
    ): Int

    @Query("UPDATE mesh_messages SET status = 'IN_FLIGHT', lastTransport = 'BLE_MESH', attemptCount = attemptCount + 1, nextAttemptAt = :deliveryDeadline, lastError = NULL WHERE messageId = :messageId AND status = 'QUEUED'")
    suspend fun claimForDelivery(messageId: String, deliveryDeadline: Long): Int

    @Query("SELECT * FROM mesh_messages WHERE senderNodeId = :localNodeId AND status = 'IN_FLIGHT' AND nextAttemptAt IS NOT NULL AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit")
    suspend fun getDueInFlight(localNodeId: String, now: Long, limit: Int): List<MessageRecordEntity>

    @Query("UPDATE mesh_messages SET status = 'QUEUED', lastTransport = 'BLE_MESH', deliveredAt = NULL, deliveryHopCount = NULL, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE messageId = :messageId AND status = 'IN_FLIGHT'")
    suspend fun requeueIfInFlight(messageId: String, nextAttemptAt: Long, lastError: String): Int

    @Query("UPDATE mesh_messages SET nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE messageId = :messageId AND status = 'QUEUED'")
    suspend fun deferQueuedMessage(
        messageId: String,
        nextAttemptAt: Long,
        lastError: String
    ): Int

    @Query("UPDATE mesh_messages SET status = 'QUEUED', lastTransport = :transport, deliveredAt = NULL, deliveryHopCount = NULL, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE messageId = :messageId")
    suspend fun scheduleRetry(
        messageId: String,
        transport: String,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String
    )

    @Query("UPDATE mesh_messages SET status = 'QUEUED', nextAttemptAt = COALESCE(nextAttemptAt, :now), lastError = :lastError WHERE senderNodeId = :localNodeId AND status = 'IN_FLIGHT'")
    suspend fun resetInFlight(localNodeId: String, now: Long, lastError: String?)

    @Query("UPDATE mesh_messages SET nextAttemptAt = :now WHERE senderNodeId = :localNodeId AND recipientNodeId = :peerNodeId AND status = 'QUEUED' AND lastError IN ('PEER_UNAVAILABLE', 'DEVICE_UNAVAILABLE')")
    suspend fun makeRecipientEligible(
        localNodeId: String,
        peerNodeId: String,
        now: Long
    )

    @Query("UPDATE mesh_messages SET nextAttemptAt = :now WHERE senderNodeId = :localNodeId AND status = 'QUEUED'")
    suspend fun makeAllQueuedEligible(localNodeId: String, now: Long)

    @Query("DELETE FROM mesh_messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM mesh_messages WHERE (UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId))")
    suspend fun deleteConversation(
        localNodeId: String,
        peerNodeId: String
    )
}
