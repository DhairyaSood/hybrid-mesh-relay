package com.hybridmesh.relay.messaging.ble

import java.util.UUID

object BleGattConstants {
    const val SERVER_READY_TIMEOUT_MS = 5_000L
    val SERVICE_UUID: UUID = UUID.fromString("7f6d1001-7b8e-4b5a-9d11-4c2a8e6f1001")
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("7f6d1002-7b8e-4b5a-9d11-4c2a8e6f1001")
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("7f6d1003-7b8e-4b5a-9d11-4c2a8e6f1001")
    val IDENTITY_CHARACTERISTIC_UUID: UUID = UUID.fromString("7f6d1004-7b8e-4b5a-9d11-4c2a8e6f1001")
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TARGET_MTU = 247
    const val DEFAULT_ATT_PAYLOAD_BYTES = 20

    const val CONNECT_TIMEOUT_MS = 8_000L
    const val SERVICE_DISCOVERY_TIMEOUT_MS = 5_000L
    const val NOTIFICATION_SETUP_TIMEOUT_MS = 4_000L
    const val MTU_TIMEOUT_MS = 3_500L
    const val WRITE_TIMEOUT_MS = 4_000L
    const val ACK_TIMEOUT_MS = 6_000L
    const val IDENTITY_READ_TIMEOUT_MS = 4_000L

    const val IDENTITY_VERSION: Byte = 1
    const val IDENTITY_NAME_MAX_BYTES = 50
}
