package com.hybridmesh.relay.model

data class LocalIdentity(
    val nodeId: String,
    val deviceName: String,
    val deviceType: NodeType = NodeType.PHONE
)