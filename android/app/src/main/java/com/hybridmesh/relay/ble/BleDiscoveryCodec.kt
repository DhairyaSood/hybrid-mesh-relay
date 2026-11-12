package com.hybridmesh.relay.ble

import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Pure encoder/decoder for the manufacturer-data discovery payload carried in the
 * BLE scan response. BleAdvertiser and BleScanner both delegate here so the byte
 * layout has exactly one definition and can be round-trip tested on the JVM.
 *
 * Discovery v4 layout (big endian):
 *   version(1) nodeUuid(16) deviceType(1) capabilities(1) meshProtocol(1) nameLength(1) name(N<=6)
 * With the 4-byte manufacturer AD header this is at most 31 bytes.
 */
object BleDiscoveryCodec {
    data class Discovery(
        val nodeId: String,
        val deviceName: String,
        val deviceType: NodeType,
        val discoveryVersion: Int,
        val meshProtocolVersion: Int,
        val canRelay: Boolean,
        val canStoreForward: Boolean,
        val supportsAttachmentHopIdentity: Boolean
    )

    sealed interface DecodeResult {
        data class Parsed(val discovery: Discovery) : DecodeResult
        data class Rejected(val reason: String) : DecodeResult
    }

    fun encode(identity: LocalIdentity): ByteArray {
        val nodeUuid = runCatching {
            UUID.fromString(identity.nodeId.removePrefix("HMR-"))
        }.getOrElse {
            UUID(0L, 0L)
        }

        val nicknameBytes = identity.deviceName
            .trim()
            .toByteArray(Charsets.UTF_8)
            .truncateUtf8(BleConstants.MESH_DISCOVERY_NAME_MAX_BYTES)

        return ByteBuffer
            .allocate(BleConstants.DISCOVERY_BASE_BYTES + nicknameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(BleConstants.DISCOVERY_VERSION)
                putLong(nodeUuid.mostSignificantBits)
                putLong(nodeUuid.leastSignificantBits)
                put(
                    when (identity.deviceType) {
                        NodeType.PHONE -> BleConstants.DEVICE_TYPE_PHONE
                        NodeType.RELAY -> BleConstants.DEVICE_TYPE_RELAY
                    }
                )
                put(
                    (BleConstants.CAPABILITY_CAN_RELAY.toInt() or
                        BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt() or
                        BleConstants.CAPABILITY_ATTACHMENT_HOP_IDENTITY.toInt()).toByte()
                )
                put(BleConstants.MESH_PROTOCOL_VERSION)
                put(nicknameBytes.size.toByte())
                put(nicknameBytes)
            }
            .array()
    }

    /** Parses one manufacturer-data payload. [data] null means the scan record carried none. */
    fun decode(data: ByteArray?, localNodeId: String): DecodeResult {
        if (data == null) return DecodeResult.Rejected("NO_MANUFACTURER_DATA")
        if (data.size < BleConstants.LEGACY_DISCOVERY_BASE_BYTES) {
            return DecodeResult.Rejected("TOO_SHORT bytes=${data.size}")
        }

        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val discoveryVersion = buffer.get()
            if (discoveryVersion != BleConstants.DISCOVERY_VERSION &&
                discoveryVersion != 3.toByte() &&
                discoveryVersion != BleConstants.LEGACY_DISCOVERY_VERSION
            ) return DecodeResult.Rejected("UNSUPPORTED_DISCOVERY_VERSION v=$discoveryVersion")
            val nodeUuid = UUID(buffer.long, buffer.long)
            val nodeId = "HMR-$nodeUuid"
            if (nodeId.equals(localNodeId, true)) return DecodeResult.Rejected("SELF")

            val deviceType = when (val code = buffer.get()) {
                BleConstants.DEVICE_TYPE_PHONE -> NodeType.PHONE
                BleConstants.DEVICE_TYPE_RELAY -> NodeType.RELAY
                else -> return DecodeResult.Rejected("UNKNOWN_DEVICE_TYPE type=$code")
            }

            val meshProtocolVersion: Int
            val canRelay: Boolean
            val canStoreForward: Boolean
            val supportsAttachmentHopIdentity: Boolean
            val nameLength: Int

            when (discoveryVersion.toInt()) {
                BleConstants.DISCOVERY_VERSION.toInt() -> {
                    if (buffer.remaining() < 3) return DecodeResult.Rejected("TRUNCATED_V4_HEADER")
                    val capabilities = buffer.get().toInt() and 0xFF
                    meshProtocolVersion = buffer.get().toInt() and 0xFF
                    canRelay = capabilities and BleConstants.CAPABILITY_CAN_RELAY.toInt() != 0
                    canStoreForward =
                        capabilities and BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt() != 0
                    supportsAttachmentHopIdentity =
                        capabilities and BleConstants.CAPABILITY_ATTACHMENT_HOP_IDENTITY.toInt() != 0
                    nameLength = buffer.get().toInt() and 0xFF
                }
                3 -> {
                    if (buffer.remaining() < 2) return DecodeResult.Rejected("TRUNCATED_V3_HEADER")
                    val capabilities = buffer.get().toInt() and 0xFF
                    meshProtocolVersion = BleConstants.LEGACY_MESH_PROTOCOL_VERSION.toInt()
                    canRelay = capabilities and BleConstants.CAPABILITY_CAN_RELAY.toInt() != 0
                    canStoreForward =
                        capabilities and BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt() != 0
                    supportsAttachmentHopIdentity = false
                    nameLength = buffer.get().toInt() and 0xFF
                }
                else -> {
                    meshProtocolVersion = 0
                    canRelay = false
                    canStoreForward = false
                    supportsAttachmentHopIdentity = false
                    nameLength = buffer.get().toInt() and 0xFF
                }
            }

            if (nameLength > BleConstants.DISCOVERY_NAME_MAX_BYTES) {
                return DecodeResult.Rejected("NAME_TOO_LONG len=$nameLength")
            }
            if (buffer.remaining() < nameLength) {
                return DecodeResult.Rejected("TRUNCATED_NAME len=$nameLength remaining=${buffer.remaining()}")
            }
            if (discoveryVersion.toInt() >= 3 && meshProtocolVersion < 1) {
                return DecodeResult.Rejected("INVALID_MESH_PROTOCOL proto=$meshProtocolVersion")
            }
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val deviceName = nameBytes.toString(Charsets.UTF_8).trim().ifBlank { "Hybrid Mesh Device" }

            DecodeResult.Parsed(
                Discovery(
                    nodeId = nodeId,
                    deviceName = deviceName,
                    deviceType = deviceType,
                    discoveryVersion = discoveryVersion.toInt(),
                    meshProtocolVersion = meshProtocolVersion,
                    canRelay = canRelay,
                    canStoreForward = canStoreForward,
                    supportsAttachmentHopIdentity = supportsAttachmentHopIdentity
                )
            )
        } catch (error: Exception) {
            // Ignore malformed advertisements rather than destabilizing discovery.
            DecodeResult.Rejected("MALFORMED ${error.javaClass.simpleName}")
        }
    }

    private fun ByteArray.truncateUtf8(maxBytes: Int): ByteArray {
        if (size <= maxBytes) return this
        var end = maxBytes
        while (
            end > 0 &&
            (this[end - 1].toInt() and 0xC0) == 0x80
        ) {
            end--
        }
        return copyOf(end)
    }
}
