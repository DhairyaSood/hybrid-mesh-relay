package com.hybridmesh.relay.messaging.mesh

data class MeshRuntimeSnapshot(
    val running: Boolean = false,
    val relayEnabled: Boolean = true,
    val pendingForwarding: Int = 0,
    val cachedPackets: Int = 0,
    val lastDeliveredHopCount: Int? = null,
    /** Time spent waiting in this process between relay acceptance and send start. */
    val lastForwardQueueDelayMs: Long? = null,
    /** Time to first peer acceptance (or all-peer failure) for the most recent fan-out. */
    val lastTransportSendDurationMs: Long? = null,
    val availableTransports: List<String> = emptyList(),
    val wifiDirectDiscoveryActive: Boolean = false,
    val connectedWifiPeers: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
