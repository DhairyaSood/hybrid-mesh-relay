package com.hybridmesh.relay.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7f6d0001-7b8e-4b5a-9d11-4c2a8e6f1001")
    val SERVICE_DATA_UUID: UUID = UUID.fromString("7f6d0002-7b8e-4b5a-9d11-4c2a8e6f1001")

    const val DISCOVERY_VERSION: Byte = 2
    const val MANUFACTURER_ID = 0xFFFF

    const val DEVICE_TYPE_PHONE: Byte = 1
    const val DEVICE_TYPE_RELAY: Byte = 2

    const val DISCOVERY_BASE_BYTES = 19 // version + node UUID + device type + name length
    const val DISCOVERY_NAME_MAX_BYTES = 8
    const val DISCOVERY_MAX_BYTES = DISCOVERY_BASE_BYTES + DISCOVERY_NAME_MAX_BYTES

    const val PEER_STALE_AFTER_MS = 8_000L
}
