package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MeshSeenPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: MeshSeenPacketEntity): Long

    @Query("SELECT * FROM mesh_seen_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun get(packetId: String): MeshSeenPacketEntity?

    @Query("UPDATE mesh_seen_packets SET bestHopCount = CASE WHEN bestHopCount > :hopCount THEN :hopCount ELSE bestHopCount END, expiresAt = CASE WHEN expiresAt < :expiresAt THEN :expiresAt ELSE expiresAt END WHERE packetId = :packetId")
    suspend fun updateObservation(packetId: String, hopCount: Int, expiresAt: Long): Int

    @Query("SELECT COUNT(*) FROM mesh_seen_packets WHERE expiresAt > :now")
    suspend fun countActive(now: Long): Int

    @Query("DELETE FROM mesh_seen_packets WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long): Int
}
