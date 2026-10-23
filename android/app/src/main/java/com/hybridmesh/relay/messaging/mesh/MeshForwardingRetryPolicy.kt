package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity

/** Bounds replay recovery after a valid origin retry reaches a previously served relay. */
internal object MeshForwardingRetryPolicy {
    const val REPLAY_COOLDOWN_MS = 20_000L
    const val MAX_DATA_FORWARD_ATTEMPTS = 8

    fun shouldReplayForwardedData(record: MeshForwardingRecordEntity, now: Long): Boolean =
        record.packetType == MeshPacket.PacketType.DATA.code.toInt() &&
            record.state == "FORWARDED" &&
            record.attemptCount < MAX_DATA_FORWARD_ATTEMPTS &&
            record.expiresAt > now &&
            (record.nextAttemptAt == null || record.nextAttemptAt <= now - REPLAY_COOLDOWN_MS)
}
