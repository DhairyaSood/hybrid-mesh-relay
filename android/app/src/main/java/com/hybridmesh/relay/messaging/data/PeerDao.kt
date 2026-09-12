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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("DELETE FROM mesh_peers WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}
