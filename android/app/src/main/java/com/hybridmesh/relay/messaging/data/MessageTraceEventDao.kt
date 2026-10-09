package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageTraceEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: MessageTraceEventEntity)

    @Query("SELECT * FROM message_trace_events WHERE messageId = :messageId ORDER BY occurredAt ASC, eventId ASC")
    fun observeForMessage(messageId: String): Flow<List<MessageTraceEventEntity>>

    @Query("DELETE FROM message_trace_events WHERE occurredAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM message_trace_events WHERE eventId NOT IN (SELECT eventId FROM message_trace_events ORDER BY occurredAt DESC, eventId DESC LIMIT :maxRows)")
    suspend fun deleteExcess(maxRows: Int): Int

    @Query("DELETE FROM message_trace_events WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("DELETE FROM message_trace_events WHERE messageId IN (SELECT messageId FROM mesh_messages WHERE (UPPER(senderNodeId) = UPPER(:localNodeId) AND UPPER(recipientNodeId) = UPPER(:peerNodeId)) OR (UPPER(senderNodeId) = UPPER(:peerNodeId) AND UPPER(recipientNodeId) = UPPER(:localNodeId)))")
    suspend fun deleteForConversation(localNodeId: String, peerNodeId: String)
}
