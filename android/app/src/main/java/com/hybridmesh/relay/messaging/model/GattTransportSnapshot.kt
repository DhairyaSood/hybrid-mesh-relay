package com.hybridmesh.relay.messaging.model

data class GattTransportSnapshot(
    val peerAddress: String? = null,
    val messageId: String? = null,
    val state: GattTransportState = GattTransportState.IDLE,
    val error: GattTransportError = GattTransportError.NONE,
    val mtu: Int? = null,
    val frameIndex: Int? = null,
    val frameCount: Int? = null,
    val bytesSent: Int = 0,
    val serviceDiscoveryOutcome: String? = null,
    val serviceDiscoveryStatus: Int? = null,
    val serviceDiscoveryException: String? = null,
    val discoveredServiceUuids: String? = null,
    val missingGattAttributes: String? = null,
    val updatedAt: Long = 0L
)
