package com.hybridmesh.relay.wifi

enum class WifiDirectStage {
    STOPPED,
    STARTING,
    DISCOVERING,
    NEGOTIATING,
    GROUP_FORMED,
    CONTROL_SERVER_STARTING,
    CONTROL_SERVER_READY,
    CONNECTING_TO_GROUP_OWNER,
    HANDSHAKING,
    SESSION_READY,
    DEGRADED
}

data class WifiDiscoveryCandidate(
    val deviceName: String,
    val deviceAddress: String,
    val protocolVersion: String? = null,
    val decision: String = "SERVICE_SEEN",
    val lastSeen: Long = System.currentTimeMillis()
)

/** Android-reported Wi-Fi Direct group membership. A Neyra session is tracked separately. */
data class WifiDirectGroupMember(
    val deviceName: String,
    val deviceAddress: String,
    val isGroupOwner: Boolean = false
)

data class WifiDirectRuntimeState(
    val stage: WifiDirectStage = WifiDirectStage.STOPPED,
    val groupOwner: Boolean = false,
    val groupFormed: Boolean = false,
    val groupOwnerAddress: String? = null,
    val controlServerReady: Boolean = false,
    val mediaServerReady: Boolean = false,
    val sessionCount: Int = 0,
    val groupClientCount: Int = 0,
    val groupMembers: List<WifiDirectGroupMember> = emptyList(),
    val candidates: List<WifiDiscoveryCandidate> = emptyList(),
    val currentAttemptId: String? = null,
    val currentPeer: String? = null,
    val locationModeEnabled: Boolean = true,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val stageSince: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
