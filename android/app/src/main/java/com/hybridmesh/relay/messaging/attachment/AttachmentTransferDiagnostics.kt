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
        "RUNTIME_CHANGED" -> "The local BLE runtime changed during the transfer. It will retry when BLE is ready."
        "GATT_DATABASE_CHANGED" -> "Android reported that the peer's GATT database changed. This attempt retried with a fresh GATT connection and service discovery."
        "ATTACHMENT_LINK_TOO_SLOW" -> "The negotiated BLE link would require too many GATT writes for a media transfer. Move the devices closer or use Wi-Fi Direct."
        "WIFI_DIRECT_TRANSFER_FAILED" -> "A Wi-Fi Direct attachment transfer was attempted but was not acknowledged."
        "ATTACHMENT_TRANSFER_INTERRUPTED" -> "The attachment transfer stopped before the next hop acknowledged it."
        "ATTACHMENT_HOP_LIMIT" -> "The attachment reached the maximum relay hop count."
        "LOCAL_ATTACHMENT_MISSING_OR_CHANGED" -> "The local attachment file is missing or no longer matches its saved checksum."
        "WIFI_DIRECT_REQUIRED_FOR_LARGE_ATTACHMENT" -> "This file is over the BLE size limit and needs a Wi-Fi Direct link."
        "ACK_TIMEOUT" -> "The peer did not acknowledge this BLE block before the timeout."
        "ACK_REJECTED" -> "The peer rejected this BLE block."
        "REMOTE_REJECTED_UNKNOWN" -> "The peer rejected this BLE attachment block but did not provide a recognized reason. The transfer remains retryable."
        "WRITE_FAILED", "WRITE_REJECTED" -> "A BLE GATT write failed before the block reached the peer."
        "CONNECTION_FAILED", "DISCONNECTED" -> "The BLE connection failed or disconnected during the transfer."
        "SERVICE_DISCOVERY_FAILED" -> "Android could not complete GATT service discovery. The outcome and status below show whether it timed out or returned an error."
        "CHARACTERISTIC_MISSING" -> "GATT discovery completed, but the expected service or characteristics were missing. See the discovered services and missing attributes below."
        "NOTIFICATION_SETUP_FAILED" -> "BLE notifications could not be enabled, so transfer acknowledgements cannot arrive."
        "FRAME_TOO_LARGE" -> "The BLE block cannot fit in the negotiated GATT packet limits."
        "MTU_NEGOTIATION_FAILED" -> "The BLE packet size could not be negotiated with the peer."
        "PERMISSION_DENIED" -> "Android denied a Bluetooth operation required by the transfer."
        "INTERNAL_ERROR" -> "The BLE sender stopped because of an unexpected internal error."
        "PROTOCOL_ERROR" -> "The peer returned an invalid BLE attachment offset or acknowledgement."
        "INVALID_MANIFEST" -> "The attachment metadata failed validation before the BLE transfer started."
        "LOCAL_FILE_MISSING_OR_CHANGED" -> "The local attachment file is missing or no longer matches its saved checksum."
        "FILE_READ_FAILED", "FILE_SEEK_FAILED" -> "The local attachment file could not be read at the required offset."
        else -> if (code.startsWith("REMOTE_REJECTED_")) {
            remoteRejectionExplanation(code.removePrefix("REMOTE_REJECTED_"))
        } else {
            code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    fun detailLabel(key: String): String = when (key) {
        "error" -> "GATT error"
        "stage" -> "Transfer stage"
        "transferred_bytes" -> "Last acknowledged bytes"
        "attachment_bytes" -> "Attachment size"
        "mtu" -> "Negotiated MTU"
        "gatt_state" -> "GATT state"
        "discovery_outcome" -> "Service discovery outcome"
        "discovery_status" -> "Android GATT status"
        "discovery_exception" -> "Discovery exception"
        "discovered_services" -> "Discovered GATT services"
        "missing_gatt_attributes" -> "Missing GATT UUIDs"
        "discovery_attempts" -> "Attachment discovery attempts"
        "first_attempt_error" -> "First discovery failure"
        "first_attempt_discovery_outcome" -> "First discovery outcome"
        "first_attempt_discovery_status" -> "First Android GATT status"
        "first_attempt_discovered_services" -> "First attempt services"
        "first_attempt_missing_attributes" -> "First attempt missing UUIDs"
        "frame" -> "GATT frame"
        "gatt_bytes" -> "Bytes in current GATT frame"
        "estimated_gatt_writes" -> "Estimated BLE writes for attachment"
        "gatt_write_limit" -> "BLE write safety limit"
        "retryable" -> "Will retry"
        "visible_peers" -> "Known nearby peers"
        "wifi_direct" -> "Wi-Fi Direct route"
        "ble_ready" -> "BLE runtime"
        "ble_endpoints" -> "Peers with BLE endpoints"
        "ble_limit_bytes" -> "BLE attachment limit"
        "transport" -> "Transport"
        "attachment_frame_version" -> "Attachment frame version"
        "reason" -> "Reason"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun String.safeField(): String = replace('|', '/').replace('\n', ' ').replace('\r', ' ')

    private fun remoteRejectionExplanation(reason: String): String = when (reason) {
        "UNKNOWN_PEER_IDENTITY" -> "This legacy BLE peer could not map the sender through discovery. Refresh BLE discovery or update both devices to the hop-identity attachment protocol."
        "SENDER_IDENTITY_MISMATCH" -> "The receiver saw a different mesh identity than the sender declared. It will retry so the peer mapping can refresh."
        "INVALID_HOP_IDENTITY" -> "The BLE attachment did not contain a valid identity for the device sending this mesh hop."
        "ATTACHMENT_PROTOCOL_MISMATCH" -> "A BLE attachment frame used a different protocol version from its BEGIN frame. Restart the attachment transfer."
        "INVALID_MANIFEST" -> "The receiver rejected the attachment metadata as invalid."
        "SIZE_LIMIT_EXCEEDED" -> "The receiver rejected this file because it exceeds BLE's attachment size limit."
        "HOP_LIMIT_EXCEEDED" -> "The receiver cannot relay this attachment because it reached the hop limit."
        "CLOCK_SKEW" -> "The receiver rejected the attachment timestamp because it is too far in the future."
        "ATTACHMENT_EXPIRED" -> "The receiver rejected this attachment because its timestamp has expired."
        "STORAGE_UNAVAILABLE" -> "The receiver could not prepare durable storage for this attachment. It will retry."
        "ATTACHMENT_METADATA_CONFLICT" -> "The receiver already has different metadata stored for this attachment ID."
        "RECEIVE_STATE_MISSING" -> "The receiver has no saved BEGIN state for this block. It will retry from the attachment setup frame."
        "RECEIVE_SOURCE_MISMATCH" -> "The receiver's saved attachment state is bound to a different BLE peer identity. It will retry after discovery refreshes."
        "CHUNK_REJECTED" -> "The receiver could not append this block at the requested offset. It will retry from the saved resume point."
        "FINISH_REJECTED" -> "The receiver could not verify or finalize the completed file. It will retry."
        "INVALID_FRAME" -> "The receiver could not decode the BLE attachment frame."
        "RECEIVER_UNAVAILABLE" -> "The receiver's attachment handler was unavailable. It will retry."
        "RECEIVER_BUSY" -> "The receiver was processing other mesh traffic. It will retry."
        "RECEIVER_TIMEOUT" -> "The receiver did not finish processing this block before its acceptance timeout. It will retry."
        "RECEIVER_ERROR" -> "The receiver encountered an internal error while processing this attachment block. It will retry."
        "ASSEMBLY_LIMIT" -> "The receiver had reached its temporary limit for concurrent BLE frames. It will retry."
        "ASSEMBLY_INVALID" -> "The receiver could not assemble a valid BLE frame. It will retry."
        else -> "The peer rejected this BLE attachment block ($reason). It will retry."
    }
}
