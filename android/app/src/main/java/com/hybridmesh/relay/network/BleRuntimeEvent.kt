package com.hybridmesh.relay.network

/** Runtime availability transitions consumed by delivery orchestration. */
enum class BleRuntimeEvent {
    TRANSPORT_UNAVAILABLE,
    TRANSPORT_AVAILABLE
}
