package com.hybridmesh.relay.ble

import java.util.UUID

object BleConstants {

    /**
     * Private 128-bit UUID identifying the Hybrid Mesh Relay
     * BLE service.
     *
     * Devices scan for this UUID to identify participating
     * Hybrid Mesh Relay nodes.
     */
    val SERVICE_UUID: UUID =
        UUID.fromString(
            "7f6d0001-7b8e-4b5a-9d11-4c2a8e6f1001"
        )

    /**
     * The service-data UUID.
     *
     * We intentionally use the same UUID as the service UUID.
     *
     * The actual node payload is placed in the BLE scan response
     * rather than the primary advertising packet. This keeps the
     * packet within the practical legacy BLE advertising limits.
     */
    val SERVICE_DATA_UUID: UUID =
        SERVICE_UUID

    /**
     * Discovery protocol version.
     */
    const val DISCOVERY_VERSION: Byte = 1

    /**
     * Payload layout:
     *
     * Byte 0     = protocol version
     * Bytes 1-4  = 8 hexadecimal Node ID characters
     * Byte 5     = device type
     *
     * Total = 6 bytes
     */
    const val PAYLOAD_SIZE = 6

    /**
     * Device type values used inside the BLE payload.
     */
    const val DEVICE_TYPE_PHONE: Byte = 1
    const val DEVICE_TYPE_RELAY: Byte = 2
}