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

data class WifiDirectRuntimeState(
    val stage: WifiDirectStage = WifiDirectStage.STOPPED,
    val groupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val controlServerReady: Boolean = false,
    val mediaServerReady: Boolean = false,
    val sessionCount: Int = 0,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val stageSince: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
