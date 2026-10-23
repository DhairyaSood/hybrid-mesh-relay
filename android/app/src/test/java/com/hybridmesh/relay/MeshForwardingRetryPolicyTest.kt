package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity
import com.hybridmesh.relay.messaging.mesh.MeshForwardingRetryPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPacket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshForwardingRetryPolicyTest {
    @Test
    fun duplicateDataReplaysAfterLostAckCooldown() {
        val forwarded = record(
            state = "FORWARDED",
            attemptCount = 1,
            lastForwardAt = 1_000L
        )

        assertFalse(MeshForwardingRetryPolicy.shouldReplayForwardedData(forwarded, 20_999L))
        assertTrue(MeshForwardingRetryPolicy.shouldReplayForwardedData(forwarded, 21_000L))
    }

    @Test
    fun replayIsBoundedByAttemptsAndPacketExpiry() {
        assertFalse(
            MeshForwardingRetryPolicy.shouldReplayForwardedData(
                record("FORWARDED", MeshForwardingRetryPolicy.MAX_DATA_FORWARD_ATTEMPTS, 1_000L),
                21_000L
            )
        )
        assertFalse(
            MeshForwardingRetryPolicy.shouldReplayForwardedData(
                record("FORWARDED", 1, 1_000L, expiresAt = 20_000L),
                21_000L
            )
        )
    }

    @Test
    fun deliveryProofAndAckPacketsNeverReplayData() {
        assertFalse(
            MeshForwardingRetryPolicy.shouldReplayForwardedData(
                record("DELIVERED", 1, 1_000L),
                21_000L
            )
        )
        assertFalse(
            MeshForwardingRetryPolicy.shouldReplayForwardedData(
                record("FORWARDED", 1, 1_000L, packetType = MeshPacket.PacketType.DELIVERY_ACK.code.toInt()),
                21_000L
            )
        )
    }

    private fun record(
        state: String,
        attemptCount: Int,
        lastForwardAt: Long?,
        expiresAt: Long = 60_000L,
        packetType: Int = MeshPacket.PacketType.DATA.code.toInt()
    ) = MeshForwardingRecordEntity(
        packetId = "HMR-33333333-3333-3333-3333-333333333333",
        messageId = "HMRM-11111111-1111-1111-1111-111111111111::retry",
        originNodeId = "HMR-11111111-1111-1111-1111-111111111111",
        destinationNodeId = "HMR-22222222-2222-2222-2222-222222222222",
        receivedFromNodeId = "HMR-44444444-4444-4444-4444-444444444444",
        receivedFromAddress = "AA:BB:CC:DD:EE:FF",
        createdAt = 1L,
        expiresAt = expiresAt,
        ttl = 7,
        hopCount = 1,
        packetType = packetType,
        messageType = "NORMAL",
        content = "payload",
        deliveryHopCount = null,
        state = state,
        attemptCount = attemptCount,
        nextAttemptAt = lastForwardAt,
        claimedAt = null,
        lastError = null
    )
}
