package com.hybridmesh.relay.messaging.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A compact, local-only observation of a message's lifecycle. Never stores message content. */
@Entity(
    tableName = "message_trace_events",
    indices = [Index(value = ["messageId", "occurredAt"]), Index(value = ["occurredAt"])]
)
data class MessageTraceEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val messageId: String,
    val packetId: String? = null,
    val occurredAt: Long,
    val eventType: String,
    val transport: String? = null,
    val peerNodeId: String? = null,
    val attemptNumber: Int? = null,
    val durationMs: Long? = null,
    val resultCode: String? = null,
    val detail: String? = null
)
