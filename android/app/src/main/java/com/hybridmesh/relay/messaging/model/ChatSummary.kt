package com.hybridmesh.relay.messaging.model

data class ChatSummary(
    val peerNodeId: String,
    val displayName: String,
    val lastMessage: String?,
    val lastActivity: Long,
    val lastStatus: DeliveryStatus?
)
