package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MeshForwardingDao {
    @Query("SELECT * FROM mesh_forwarding WHERE state = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) AND expiresAt > :now ORDER BY COALESCE(nextAttemptAt, 0) ASC, createdAt ASC LIMIT :limit")
    suspend fun getPending(now: Long, limit: Int): List<MeshForwardingRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: MeshForwardingRecordEntity): Long

    @Query("SELECT * FROM mesh_forwarding WHERE packetId = :packetId LIMIT 1")
    suspend fun get(packetId: String): MeshForwardingRecordEntity?

    @Query("UPDATE mesh_forwarding SET state = 'FORWARDING', attemptCount = attemptCount + 1, nextAttemptAt = NULL, lastError = NULL WHERE packetId = :packetId AND state = 'PENDING'")
    suspend fun claim(packetId: String): Int

    @Query("""
        UPDATE mesh_forwarding
        SET state = 'PENDING',
            ttl = :ttl,
            hopCount = :hopCount,
            receivedFromNodeId = :receivedFromNodeId,
            receivedFromAddress = :receivedFromAddress,
            nextAttemptAt = :nextAttemptAt,
            lastError = 'BETTER_COPY_RECEIVED'
        WHERE packetId = :packetId
          AND state IN ('PENDING', 'FORWARDED')
          AND hopCount > :hopCount
    """)
    suspend fun improveRecord(
        packetId: String,
        ttl: Int,
        hopCount: Int,
        receivedFromNodeId: String?,
        receivedFromAddress: String?,
        nextAttemptAt: Long
    ): Int

    @Query("UPDATE mesh_forwarding SET state = 'FORWARDED', nextAttemptAt = NULL, lastError = NULL WHERE packetId = :packetId AND state IN ('PENDING', 'FORWARDING')")
    suspend fun markForwarded(packetId: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, lastError = :error WHERE packetId = :packetId AND state = 'FORWARDED' AND expiresAt > :now")
    suspend fun requeueForwarded(packetId: String, now: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'EXPIRED', nextAttemptAt = NULL, lastError = :error WHERE packetId = :packetId")
    suspend fun markInvalid(packetId: String, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :nextAttemptAt, lastError = :error WHERE packetId = :packetId AND state = 'FORWARDING'")
    suspend fun scheduleRetry(packetId: String, nextAttemptAt: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, lastError = :error WHERE state = 'FORWARDING' AND expiresAt > :now")
    suspend fun resetStaleForwarding(now: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, lastError = :error WHERE state = 'FORWARDING' AND expiresAt > :now")
    suspend fun requeueOnTransportLoss(now: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'EXPIRED', nextAttemptAt = NULL, lastError = 'EXPIRED' WHERE packetId = :packetId")
    suspend fun markExpired(packetId: String): Int

    @Query("SELECT COUNT(*) FROM mesh_forwarding WHERE state IN ('PENDING', 'FORWARDING') AND expiresAt > :now")
    suspend fun countPending(now: Long): Int

    @Query("DELETE FROM mesh_forwarding WHERE expiresAt <= :now OR (state IN ('FORWARDED', 'EXPIRED') AND expiresAt <= :now)")
    suspend fun purgeExpired(now: Long): Int
}
