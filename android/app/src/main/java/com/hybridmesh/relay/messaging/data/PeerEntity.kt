package com.hybridmesh.relay.messaging.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val deviceType: String,
    val address: String?,
    val lastRssi: Int?,
    val lastSeenAt: Long?,
    val meshProtocolVersion: Int = 0,
    val canRelay: Boolean = false,
    val canStoreForward: Boolean = false
)
