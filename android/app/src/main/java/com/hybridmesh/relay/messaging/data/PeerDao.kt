package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM mesh_peers ORDER BY displayName COLLATE NOCASE, nodeId")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM mesh_peers WHERE nodeId = :nodeId LIMIT 1")
    suspend fun get(nodeId: String): PeerEntity?

    @Query("SELECT COUNT(*) FROM mesh_peers")
    suspend fun count(): Int

    @Query("DELETE FROM mesh_peers WHERE nodeId = (SELECT nodeId FROM mesh_peers WHERE isSavedForChat = 0 ORDER BY COALESCE(lastSeenAt, 0) ASC, nodeId ASC LIMIT 1)")
    suspend fun deleteOldestUnsaved(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE mesh_peers SET isSavedForChat = 1 WHERE nodeId = :nodeId")
    suspend fun saveForChat(nodeId: String)

    @Query("UPDATE mesh_peers SET isSavedForChat = 0 WHERE nodeId = :nodeId")
    suspend fun removeFromChats(nodeId: String)

    @Query("DELETE FROM mesh_peers WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}
