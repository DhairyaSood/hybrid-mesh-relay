package com.hybridmesh.relay.messaging.attachment

/** Compact, durable attachment diagnostics stored in AttachmentRecordEntity.lastError. */
object AttachmentTransferDiagnostics {
    private const val PREFIX = "ATTACHMENT_DIAG"

    data class Parsed(
        val code: String,
        val details: List<Pair<String, String>>
    )

    fun encode(code: String, details: Map<String, Any?> = emptyMap()): String = buildString {
        append(PREFIX)
        append('|')
        append(code.safeField())
        details.forEach { (key, value) ->
            if (value != null) {
                append('|')
                append(key.safeField())
                append('=')
                append(value.toString().safeField())
            }
        }
    }

    fun parse(value: String?): Parsed? {
        val parts = value?.split('|') ?: return null
        if (parts.size < 2 || parts.first() != PREFIX || parts[1].isBlank()) return null
        val details = parts.drop(2).mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) null
            else field.substring(0, separator) to field.substring(separator + 1)
        }
        return Parsed(parts[1], details)
    }

    fun explanation(code: String): String = when (code) {
        "NO_COMPATIBLE_LINK" -> "No usable next-hop link is currently available. The attachment is queued and will retry."
        "NO_DISCOVERED_PEER" -> "No nearby mesh peer is currently known to this device."
        "NO_RELAY_CAPABLE_PEER" -> "Nearby peers exist, but none has a usable route to the destination or relay capability."
        "BLE_SIZE_LIMIT" -> "This attachment exceeds BLE's 5 MiB limit and no Wi-Fi Direct route is connected."
        "BLE_RUNTIME_NOT_READY" -> "BLE is not ready on this device. Check the BLE toggle, Bluetooth, permissions, and GATT server state."
        "BLE_DISABLED" -> "BLE is disabled in transport settings on this device."
        "BLE_NOT_SUPPORTED" -> "This device does not report BLE support."
        "BLE_PERMISSION_MISSING" -> "The BLE permissions required for a transfer are not granted."
        "BLUETOOTH_OFF" -> "Bluetooth is turned off on this device."
        "BLE_GATT_SERVER_NOT_READY" -> "The local BLE GATT server is not ready to accept acknowledgements."
        "BLE_ENDPOINT_UNAVAILABLE" -> "The nearby peer has no usable BLE address in the mesh directory."
        "BLE_PEER_BUSY" -> "Another BLE operation is already using this peer. The attachment will retry."
        "BLE_DEVICE_NOT_FOUND" -> "Android could not resolve the peer's BLE device address."
        "BLE_ATTACHMENT_SEND_FAILED" -> "A BLE attachment transfer was attempted but did not complete. See the stage and GATT details below."
        "ATTACHMENT_LINK_TOO_SLOW" -> "The negotiated BLE link would require too many GATT writes for a media transfer. Move the devices closer or use Wi-Fi Direct."
        "WIFI_DIRECT_TRANSFER_FAILED" -> "A Wi-Fi Direct attachment transfer was attempted but was not acknowledged."
        "ATTACHMENT_TRANSFER_INTERRUPTED" -> "The attachment transfer stopped before the next hop acknowledged it."
        "ATTACHMENT_HOP_LIMIT" -> "The attachment reached the maximum relay hop count."
        "LOCAL_ATTACHMENT_MISSING_OR_CHANGED" -> "The local attachment file is missing or no longer matches its saved checksum."
        "WIFI_DIRECT_REQUIRED_FOR_LARGE_ATTACHMENT" -> "This file is over the BLE size limit and needs a Wi-Fi Direct link."
        "ACK_TIMEOUT" -> "The peer did not acknowledge this BLE block before the timeout."
        "ACK_REJECTED" -> "The peer rejected this BLE block."
        "WRITE_FAILED", "WRITE_REJECTED" -> "A BLE GATT write failed before the block reached the peer."
        "CONNECTION_FAILED", "DISCONNECTED" -> "The BLE connection failed or disconnected during the transfer."
        "SERVICE_DISCOVERY_FAILED", "CHARACTERISTIC_MISSING" -> "The peer did not expose the expected BLE transfer service."
        "NOTIFICATION_SETUP_FAILED" -> "BLE notifications could not be enabled, so transfer acknowledgements cannot arrive."
        "FRAME_TOO_LARGE" -> "The BLE block cannot fit in the negotiated GATT packet limits."
        "MTU_NEGOTIATION_FAILED" -> "The BLE packet size could not be negotiated with the peer."
        "PERMISSION_DENIED" -> "Android denied a Bluetooth operation required by the transfer."
        "INTERNAL_ERROR" -> "The BLE sender stopped because of an unexpected internal error."
        "PROTOCOL_ERROR" -> "The peer returned an invalid BLE attachment offset or acknowledgement."
        "INVALID_MANIFEST" -> "The attachment metadata failed validation before the BLE transfer started."
        "LOCAL_FILE_MISSING_OR_CHANGED" -> "The local attachment file is missing or no longer matches its saved checksum."
        "FILE_READ_FAILED", "FILE_SEEK_FAILED" -> "The local attachment file could not be read at the required offset."
        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun detailLabel(key: String): String = when (key) {
        "error" -> "GATT error"
        "stage" -> "Transfer stage"
        "transferred_bytes" -> "Last acknowledged bytes"
        "attachment_bytes" -> "Attachment size"
        "mtu" -> "Negotiated MTU"
        "gatt_state" -> "GATT state"
        "frame" -> "GATT frame"
        "gatt_bytes" -> "Bytes in current GATT frame"
        "estimated_gatt_writes" -> "Estimated BLE writes for attachment"
        "gatt_write_limit" -> "BLE write safety limit"
        "visible_peers" -> "Known nearby peers"
        "wifi_direct" -> "Wi-Fi Direct route"
        "ble_ready" -> "BLE runtime"
        "ble_endpoints" -> "Peers with BLE endpoints"
        "ble_limit_bytes" -> "BLE attachment limit"
        "transport" -> "Transport"
        "reason" -> "Reason"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun String.safeField(): String = replace('|', '/').replace('\n', ' ').replace('\r', ' ')
}
