package com.hybridmesh.relay.ble

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7f6d0001-7b8e-4b5a-9d11-4c2a8e6f1001")
    val SERVICE_DATA_UUID: UUID = UUID.fromString("7f6d0002-7b8e-4b5a-9d11-4c2a8e6f1001")

    const val DISCOVERY_VERSION: Byte = 4
    const val LEGACY_DISCOVERY_VERSION: Byte = 2
    const val MESH_PROTOCOL_VERSION: Byte = 2
    // Discovery v3 advertised mesh capabilities but had no protocol-version
    // field. Devices using that format predate mesh packet v2.
    const val LEGACY_MESH_PROTOCOL_VERSION: Byte = 1
    const val CAPABILITY_CAN_RELAY: Byte = 0x01
    const val CAPABILITY_CAN_STORE_FORWARD: Byte = 0x02
    // Attachment frame v2 includes the immediate sender identity for each mesh hop.
    const val CAPABILITY_ATTACHMENT_HOP_IDENTITY: Byte = 0x04
    const val MANUFACTURER_ID = 0xFFFF

    const val DEVICE_TYPE_PHONE: Byte = 1
    const val DEVICE_TYPE_RELAY: Byte = 2

    const val LEGACY_DISCOVERY_BASE_BYTES = 19 // version + node UUID + device type + name length
    const val DISCOVERY_BASE_BYTES = 21 // + mesh capability + protocol version
    const val DISCOVERY_NAME_MAX_BYTES = 8
    const val MESH_DISCOVERY_NAME_MAX_BYTES = 6 // keep full scan-response manufacturer data within 31 bytes
    const val DISCOVERY_MAX_BYTES = DISCOVERY_BASE_BYTES + MESH_DISCOVERY_NAME_MAX_BYTES

    const val PEER_STALE_AFTER_MS = 8_000L

    fun encodeUtf8Truncated(value: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        val encoded = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Text contains malformed UTF-16", failure)
        }
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (bytes.size <= maxBytes) return bytes

        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
