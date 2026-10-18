package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM mesh_attachments ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AttachmentRecordEntity>>

    @Query("SELECT * FROM mesh_attachments WHERE (UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId))")
    fun observeConversation(localNodeId: String, peerNodeId: String): Flow<List<AttachmentRecordEntity>>

    @Query("SELECT * FROM mesh_attachments WHERE (UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId))")
    suspend fun getForConversation(localNodeId: String, peerNodeId: String): List<AttachmentRecordEntity>

    @Query("SELECT * FROM mesh_attachments WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): AttachmentRecordEntity?

    @Query("SELECT * FROM mesh_attachments WHERE messageId = :messageId LIMIT 1")
    fun observe(messageId: String): Flow<AttachmentRecordEntity?>

    @Query("SELECT * FROM mesh_attachments WHERE (senderNodeId = :localNodeId AND status IN ('QUEUED', 'WAITING_WIFI', 'SENDING')) OR (localPath IS NOT NULL AND UPPER(recipientNodeId) != UPPER(:localNodeId) AND status IN ('RELAY_QUEUED', 'WAITING_WIFI', 'SENDING')) ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getPendingOutgoing(localNodeId: String, limit: Int): List<AttachmentRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttachmentRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AttachmentRecordEntity)

    @Query("UPDATE mesh_attachments SET status = :status, transferredBytes = :transferredBytes, updatedAt = :updatedAt, lastError = :lastError WHERE messageId = :messageId")
    suspend fun updateProgress(messageId: String, status: String, transferredBytes: Long, updatedAt: Long, lastError: String?): Int

    @Query("UPDATE mesh_attachments SET status = 'WAITING_WIFI', updatedAt = :now, lastError = 'TRANSFER_INTERRUPTED' WHERE status IN ('SENDING', 'RECEIVING')")
    suspend fun resetInterruptedTransfers(now: Long): Int

    @Query("UPDATE mesh_attachments SET status = :status, localPath = :localPath, updatedAt = :updatedAt, lastError = :lastError WHERE messageId = :messageId")
    suspend fun updateStatusAndPath(messageId: String, status: String, localPath: String?, updatedAt: Long, lastError: String?): Int

    @Query("DELETE FROM mesh_attachments WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM mesh_attachments WHERE (UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId))")
    suspend fun deleteConversation(localNodeId: String, peerNodeId: String)
}
