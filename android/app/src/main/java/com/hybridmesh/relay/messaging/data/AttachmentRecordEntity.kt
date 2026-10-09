package com.hybridmesh.relay.messaging.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/** Metadata and durable local transfer state. File bytes live in app-private storage. */
@Entity(tableName = "mesh_attachments")
data class AttachmentRecordEntity(
    @PrimaryKey val messageId: String,
    val senderNodeId: String,
    val recipientNodeId: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val localPath: String?,
    val status: String,
    val receivedFromNodeId: String? = null,
    @ColumnInfo(defaultValue = "0") val hopCount: Int = 0,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val transferredBytes: Long = 0L,
    val updatedAt: Long,
    val lastError: String? = null
)
