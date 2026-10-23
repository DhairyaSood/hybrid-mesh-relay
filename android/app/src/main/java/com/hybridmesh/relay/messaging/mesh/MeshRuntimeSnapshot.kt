package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.wifi.WifiDiscoveryCandidate
import com.hybridmesh.relay.wifi.WifiDirectPeerRuntimeState

data class MeshRuntimeSnapshot(
    val running: Boolean = false,
    val bleEnabled: Boolean = true,
    val wifiDirectEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
    val pendingForwarding: Int = 0,
    val cachedPackets: Int = 0,
    val lastDeliveredHopCount: Int? = null,
    /** Time spent waiting in this process between relay acceptance and send start. */
    val lastForwardQueueDelayMs: Long? = null,
    /** Time to first peer acceptance (or all-peer failure) for the most recent fan-out. */
    val lastTransportSendDurationMs: Long? = null,
    val currentAttachmentThroughputMbps: Double? = null,
    val lastAttachmentThroughputMbps: Double? = null,
    val attachmentTransferActive: Boolean = false,
    val attachmentTransport: String? = null,
    val availableTransports: List<String> = emptyList(),
    val wifiDirectDiscoveryActive: Boolean = false,
    val connectedWifiPeers: Int = 0,
    val wifiDirectGroupClientCount: Int = 0,
    val wifiDirectGroupFormed: Boolean = false,
    val wifiDirectGroupOwner: Boolean = false,
    val wifiDirectLocationModeEnabled: Boolean = true,
    val wifiDirectCandidates: List<WifiDiscoveryCandidate> = emptyList(),
    val wifiDirectPeers: List<WifiDirectPeerRuntimeState> = emptyList(),
    val wifiDirectStage: String = "STOPPED",
    val wifiDirectCurrentAttemptId: String? = null,
    val wifiDirectCurrentPeer: String? = null,
    val wifiDirectRetryCount: Int = 0,
    val wifiDirectStageSince: Long = 0L,
    val wifiDirectControlServerReady: Boolean = false,
    val wifiDirectMediaServerReady: Boolean = false,
    val wifiDirectGroupOwnerAddress: String? = null,
    val wifiDirectLastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
