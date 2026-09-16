package com.hybridmesh.relay.messaging.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_messages")
data class MessageRecordEntity(
    @PrimaryKey val messageId: String,
    val senderNodeId: String,
    val recipientNodeId: String,
    val content: String,
    val createdAt: Long,
    val messageType: String,
    val status: String,
    val lastTransport: String?,
    val deliveredAt: Long?,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val deliveryHopCount: Int? = null
)
