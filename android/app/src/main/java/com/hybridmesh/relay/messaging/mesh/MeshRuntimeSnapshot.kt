package com.hybridmesh.relay.messaging.mesh

data class MeshRuntimeSnapshot(
    val running: Boolean = false,
    val relayEnabled: Boolean = true,
    val pendingForwarding: Int = 0,
    val cachedPackets: Int = 0,
    val lastDeliveredHopCount: Int? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
