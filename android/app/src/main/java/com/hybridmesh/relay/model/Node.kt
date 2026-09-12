package com.hybridmesh.relay.model

enum class NodeType {
    PHONE,
    RELAY
}

enum class NodeConnectionState {
    UNKNOWN,
    AVAILABLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

data class Node(
    val id: String,
    val name: String?,
    val type: NodeType,
    val connectionState: NodeConnectionState = NodeConnectionState.UNKNOWN,
    val transport: String? = null,
    val signalStrength: Int? = null,
    val hopCount: Int = 0
)