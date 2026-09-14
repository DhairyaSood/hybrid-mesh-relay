package com.hybridmesh.relay.network

/** Real Bluetooth adapter lifecycle as observed by the mesh runtime. */
enum class BluetoothState {
    UNSUPPORTED,
    OFF,
    TURNING_ON,
    TURNING_OFF,
    ON,
    ERROR
}
