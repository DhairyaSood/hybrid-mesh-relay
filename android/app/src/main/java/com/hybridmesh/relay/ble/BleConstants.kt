package com.hybridmesh.relay.ble

import java.util.UUID

object BleConstants {

    /**
     * Private 128-bit service identifying Hybrid Mesh Relay.
     */
    val SERVICE_UUID: UUID =
        UUID.fromString(
            "7f6d0001-7b8e-4b5a-9d11-4c2a8e6f1001"
        )

    /**
     * Compact discovery service-data payload.
     *
     * Payload:
     * byte 0     = protocol version
     * bytes 1-4  = Node ID suffix
     * byte 5     = device type
     */
    val SERVICE_DATA_UUID: UUID =
        UUID.fromString(
            "7f6d0002-7b8e-4b5a-9d11-4c2a8e6f1001"
        )

    const val DISCOVERY_VERSION: Byte = 1
    const val PAYLOAD_SIZE = 6

    const val DEVICE_TYPE_PHONE: Byte = 1
    const val DEVICE_TYPE_RELAY: Byte = 2

    const val PEER_TIMEOUT_MS = 8_000L
}
