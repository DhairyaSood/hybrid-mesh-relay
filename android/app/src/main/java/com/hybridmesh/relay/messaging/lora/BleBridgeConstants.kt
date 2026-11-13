package com.hybridmesh.relay.messaging.lora

import java.util.UUID

/**
 * NEYRA-LORA ESP32 bridge GATT interface. Mirrors firmware/relay/include/config.h and
 * protocol/packet-format.md ("BLE bridge framing"); change all three together.
 *
 * Deliberately separate from the phone mesh UUIDs (discovery 7f6d0001, mesh service
 * 7f6d1001) so the bridge is never mistaken for a mesh peer and vice versa.
 */
object BleBridgeConstants {
    const val DEVICE_NAME = "NEYRA-LORA"
    val SERVICE_UUID: UUID = UUID.fromString("7f6d2001-7b8e-4b5a-9d11-4c2a8e6f1001")
    /** Phone -> bridge (write with response). */
    val WRITE_CHAR_UUID: UUID = UUID.fromString("7f6d2002-7b8e-4b5a-9d11-4c2a8e6f1001")
    /** Bridge -> phone (notify). */
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("7f6d2003-7b8e-4b5a-9d11-4c2a8e6f1001")
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TARGET_MTU = 247
    const val MIN_ATT_MTU = 23
    /** ATT write/notify header taken out of every MTU. */
    const val ATT_HEADER_BYTES = 3
    /** chunkIndex(1) + chunkCount(1). */
    const val CHUNK_HEADER_BYTES = 2
    /** Whole mesh packet limit: the SX127x LoRa payload maximum. */
    const val MAX_PACKET_BYTES = 255
    const val REASSEMBLY_TIMEOUT_MS = 2_000L

    const val SCAN_TIMEOUT_MS = 15_000L
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val SETUP_STEP_TIMEOUT_MS = 5_000L
    const val WRITE_TIMEOUT_MS = 3_000L
    /** Reconnect backoff; Android throttles apps that start more than 5 scans in 30 s. */
    val RECONNECT_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L)
}
