package com.hybridmesh.relay.messaging.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mesh_seen_packets",
    indices = [Index(value = ["expiresAt"])]
)
data class MeshSeenPacketEntity(
    @PrimaryKey val packetId: String,
    val messageId: String,
    val firstSeenAt: Long,
    val expiresAt: Long,
    val bestHopCount: Int
)
