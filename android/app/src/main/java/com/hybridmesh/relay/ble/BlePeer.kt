package com.hybridmesh.relay.ble

import com.hybridmesh.relay.model.NodeType

data class BlePeer(
    val nodeId: String,
    val deviceName: String,
    val deviceType: NodeType,
    val address: String,
    val rssi: Int,
    val lastSeen: Long
)