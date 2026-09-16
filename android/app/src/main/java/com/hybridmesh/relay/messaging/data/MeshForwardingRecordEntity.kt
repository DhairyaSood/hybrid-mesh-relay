package com.hybridmesh.relay.messaging.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mesh_forwarding",
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["state", "nextAttemptAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class MeshForwardingRecordEntity(
    @PrimaryKey val packetId: String,
    val messageId: String,
    val originNodeId: String,
    val destinationNodeId: String,
    val receivedFromNodeId: String?,
    val receivedFromAddress: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val packetType: Int,
    val messageType: String,
    val content: String,
    val deliveryHopCount: Int?,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAt: Long?,
    val lastError: String?
)
