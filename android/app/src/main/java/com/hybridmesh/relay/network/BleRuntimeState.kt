package com.hybridmesh.relay.network

enum class BleRuntimeState {
    DISABLED,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    STARTING,
    RECOVERING,
    READY,
    DEGRADED
}
