package com.hybridmesh.relay.network

/** User-facing startup/recovery phases. This is observational and never owns persistence. */
enum class InitializationState {
    BOOTSTRAPPING,
    CHECKING_PERMISSIONS,
    STARTING_BLE,
    STARTING_GATT,
    STARTING_DISCOVERY,
    READY,
    RECOVERING,
    DEGRADED,
    BLUETOOTH_OFF,
    PERMISSION_REQUIRED
}
