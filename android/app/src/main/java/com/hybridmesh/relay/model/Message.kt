package com.hybridmesh.relay.model

data class Message(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.NORMAL,
    val status: MessageStatus = MessageStatus.QUEUED,
    val route: RouteInfo? = null
)