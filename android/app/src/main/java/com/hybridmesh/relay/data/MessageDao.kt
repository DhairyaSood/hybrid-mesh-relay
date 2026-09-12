package com.hybridmesh.relay.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query(
        """
        SELECT * FROM messages
        ORDER BY timestamp DESC
        """
    )
    fun observeMessages(): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE id = :messageId
        LIMIT 1
        """
    )
    suspend fun getById(messageId: String): MessageEntity?

    @Query(
        """
        UPDATE messages
        SET status = :status
        WHERE id = :messageId
        """
    )
    suspend fun updateStatus(
        messageId: String,
        status: String
    )

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}