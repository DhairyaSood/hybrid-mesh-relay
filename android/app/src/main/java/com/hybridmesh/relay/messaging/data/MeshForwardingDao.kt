package com.hybridmesh.relay.messaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MeshForwardingDao {
    @Query("SELECT * FROM mesh_forwarding WHERE state = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) AND expiresAt > :now ORDER BY CASE WHEN packetType IN (2, 3) THEN 0 ELSE 1 END, COALESCE(nextAttemptAt, 0) ASC, createdAt ASC LIMIT :limit")
    suspend fun getPending(now: Long, limit: Int): List<MeshForwardingRecordEntity>

    @Query("SELECT MIN(nextAttemptAt) FROM mesh_forwarding WHERE state = 'PENDING' AND expiresAt > :now AND nextAttemptAt IS NOT NULL")
    suspend fun getEarliestNextAttemptAt(now: Long): Long?

    @Query("UPDATE mesh_forwarding SET nextAttemptAt = :now, lastError = NULL WHERE state = 'PENDING' AND expiresAt > :now AND lastError IN ('NO_MESH_NEIGHBOR', 'MESH_TRANSPORT_FAILED', 'BLE_TRANSPORT_UNAVAILABLE', 'WIFI_DIRECT_TRANSPORT_UNAVAILABLE')")
    suspend fun makePendingEligible(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: MeshForwardingRecordEntity): Long

    @Query("SELECT * FROM mesh_forwarding WHERE packetId = :packetId LIMIT 1")
    suspend fun get(packetId: String): MeshForwardingRecordEntity?

    @Query("UPDATE mesh_forwarding SET state = 'FORWARDING', attemptCount = attemptCount + 1, nextAttemptAt = NULL, claimedAt = :now, lastError = NULL WHERE packetId = :packetId AND state = 'PENDING'")
    suspend fun claim(packetId: String, now: Long): Int

    @Query("""
        UPDATE mesh_forwarding
        SET state = CASE WHEN state = 'FORWARDING' THEN 'FORWARDING' ELSE 'PENDING' END,
            ttl = :ttl,
            hopCount = :hopCount,
            receivedFromNodeId = :receivedFromNodeId,
            receivedFromAddress = :receivedFromAddress,
            routeTrace = :routeTrace,
            routeTraceComplete = :routeTraceComplete,
            claimedAt = CASE WHEN state = 'FORWARDING' THEN claimedAt ELSE NULL END,
            nextAttemptAt = CASE
                WHEN state = 'FORWARDING' THEN NULL
                WHEN state = 'EXPIRED' THEN :now
                ELSE :nextAttemptAt
            END,
            lastError = CASE WHEN state = 'FORWARDING' THEN 'BETTER_COPY_RECEIVED_DURING_FORWARD' ELSE 'BETTER_COPY_RECEIVED' END
        WHERE packetId = :packetId
          AND state IN ('PENDING', 'FORWARDED', 'FORWARDING', 'EXPIRED')
          AND (state != 'EXPIRED' OR expiresAt > :now)
          AND hopCount > :hopCount
    """)
    suspend fun improveRecord(
        packetId: String,
        ttl: Int,
        hopCount: Int,
        receivedFromNodeId: String?,
        receivedFromAddress: String?,
        routeTrace: String,
        routeTraceComplete: Boolean,
        nextAttemptAt: Long,
        now: Long
    ): Int

    @Query("UPDATE mesh_forwarding SET state = 'FORWARDED', nextAttemptAt = :forwardedAt, claimedAt = NULL, lastError = NULL WHERE packetId = :packetId AND state = 'FORWARDING' AND (lastError IS NULL OR lastError != 'BETTER_COPY_RECEIVED_DURING_FORWARD')")
    suspend fun markForwarded(packetId: String, forwardedAt: Long): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, claimedAt = NULL, lastError = 'BETTER_COPY_RECEIVED' WHERE packetId = :packetId AND state = 'FORWARDING' AND lastError = 'BETTER_COPY_RECEIVED_DURING_FORWARD' AND expiresAt > :now")
    suspend fun requeueAfterBetterCopy(packetId: String, now: Long): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, lastError = :error WHERE packetId = :packetId AND state = 'FORWARDED' AND expiresAt > :now")
    suspend fun requeueForwarded(packetId: String, now: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, lastError = 'DUPLICATE_DATA_REPLAY' WHERE packetId = :packetId AND packetType = 1 AND state = 'FORWARDED' AND attemptCount < :maxAttempts AND expiresAt > :now AND (nextAttemptAt IS NULL OR nextAttemptAt <= :retryEligibleBefore)")
    suspend fun requeueForwardedDataAfterRetry(
        packetId: String,
        now: Long,
        retryEligibleBefore: Long,
        maxAttempts: Int
    ): Int

    @Query("UPDATE mesh_forwarding SET state = 'DELIVERED', nextAttemptAt = NULL, claimedAt = NULL, lastError = NULL WHERE messageId = :messageId AND originNodeId = :originNodeId AND destinationNodeId = :destinationNodeId AND packetType = 1 AND state IN ('PENDING', 'FORWARDING', 'FORWARDED', 'EXPIRED')")
    suspend fun markDataDelivered(messageId: String, originNodeId: String, destinationNodeId: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'EXPIRED', nextAttemptAt = NULL, lastError = :error WHERE packetId = :packetId AND state != 'DELIVERED'")
    suspend fun markInvalid(packetId: String, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :nextAttemptAt, claimedAt = NULL, lastError = 'DUPLICATE_FORWARD_REPAIRED' WHERE packetId = :packetId AND state = 'EXPIRED' AND expiresAt > :now")
    suspend fun repairExpired(packetId: String, now: Long, nextAttemptAt: Long): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :nextAttemptAt, claimedAt = NULL, lastError = :error WHERE packetId = :packetId AND state = 'FORWARDING'")
    suspend fun scheduleRetry(packetId: String, nextAttemptAt: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, claimedAt = NULL, lastError = :error WHERE state = 'FORWARDING' AND expiresAt > :now AND (claimedAt IS NULL OR claimedAt <= :staleBefore)")
    suspend fun resetStaleForwarding(now: Long, staleBefore: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'PENDING', nextAttemptAt = :now, claimedAt = NULL, lastError = :error WHERE state = 'FORWARDING' AND expiresAt > :now")
    suspend fun requeueOnTransportLoss(now: Long, error: String): Int

    @Query("UPDATE mesh_forwarding SET state = 'EXPIRED', nextAttemptAt = NULL, lastError = 'EXPIRED' WHERE packetId = :packetId AND state != 'DELIVERED'")
    suspend fun markExpired(packetId: String): Int

    @Query("SELECT COUNT(*) FROM mesh_forwarding WHERE state IN ('PENDING', 'FORWARDING') AND expiresAt > :now")
    suspend fun countPending(now: Long): Int

    @Query("DELETE FROM mesh_forwarding WHERE expiresAt <= :now OR (state IN ('FORWARDED', 'EXPIRED') AND expiresAt <= :now)")
    suspend fun purgeExpired(now: Long): Int
}
