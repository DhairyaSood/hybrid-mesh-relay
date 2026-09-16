package com.hybridmesh.relay.messaging.mesh

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Wire representation of one logical Neyra mesh packet. */
data class MeshPacket(
    val packetId: String,
    val messageId: String,
    val originNodeId: String,
    val destinationNodeId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val packetType: PacketType,
    val messageType: String,
    val content: String,
    val deliveryHopCount: Int? = null
) {
    enum class PacketType(val code: Byte) {
        DATA(1),
        DELIVERY_ACK(2)
    }

}

/**
 * Compact, versioned application-network packet. GATT remains responsible for
 * chunking this byte stream; this codec owns only mesh semantics.
 */
object MeshPacketCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x4D) // "NM"
    const val PROTOCOL_VERSION: Int = 1
    private const val VERSION: Byte = PROTOCOL_VERSION.toByte()
    private const val FIXED_HEADER_BYTES = 2 + 1 + 1 + 16 + 16 + 16 + 2 + 2 + 2 + 1 + 1 + 1 + 8 + 8
    private const val MIN_BYTES = FIXED_HEADER_BYTES + 4
    const val MAX_PACKET_BYTES = 16 * 1024
    private const val MAX_TEXT_BYTES = 12 * 1024
    const val INITIAL_TTL = 8
    private const val MAX_TTL_WIRE = 32

    fun encode(packet: MeshPacket): ByteArray {
        val packetUuid = uuid(packet.packetId)
        val messageId = packet.messageId.toByteArray(Charsets.UTF_8)
        val messageType = packet.messageType.toByteArray(Charsets.UTF_8)
        val content = packet.content.toByteArray(Charsets.UTF_8)

        require(messageId.size <= MAX_TEXT_BYTES) { "messageId too large" }
        require(messageType.size <= 255) { "messageType too large" }
        require(content.size <= MAX_TEXT_BYTES) { "mesh payload too large" }
        require(packet.ttl in 0..MAX_TTL_WIRE) { "ttl out of range" }
        require(packet.hopCount in 0..MAX_TTL_WIRE) { "hopCount out of range" }
        require(packet.ttl + packet.hopCount == INITIAL_TTL) {
            "invalid ttl/hopCount relationship"
        }
        require(packet.messageId.isNotBlank()) { "messageId is blank" }
        require(packet.messageType.isNotBlank()) { "messageType is blank" }
        require(uuid(packet.originNodeId) != uuid(packet.destinationNodeId)) {
            "origin and destination must differ"
        }
        when (packet.packetType) {
            MeshPacket.PacketType.DATA -> {
                require(packet.deliveryHopCount == null) {
                    "DATA packets cannot contain deliveryHopCount"
                }
            }
            MeshPacket.PacketType.DELIVERY_ACK -> {
                require(packet.messageType == "DELIVERY_ACK") {
                    "DELIVERY_ACK packet has invalid messageType"
                }
                require(packet.content.isEmpty()) {
                    "DELIVERY_ACK packet cannot contain content"
                }
                require(packet.deliveryHopCount != null) {
                    "DELIVERY_ACK requires deliveryHopCount"
                }
            }
        }
        require(packet.deliveryHopCount == null || packet.deliveryHopCount in 0..MAX_TTL_WIRE) {
            "deliveryHopCount out of range"
        }

        val header = ByteArrayOutputStream()
        header.write(MAGIC)
        header.write(VERSION.toInt())
        header.write(packet.packetType.code.toInt())
        header.write(uuidBytes(packetUuid))
        header.write(uuidBytes(uuid(packet.originNodeId)))
        header.write(uuidBytes(uuid(packet.destinationNodeId)))
        writeShort(header, messageId.size)
        writeShort(header, messageType.size)
        writeShort(header, content.size)
        header.write(packet.ttl)
        header.write(packet.hopCount)
        header.write(if (packet.deliveryHopCount == null) 0 else packet.deliveryHopCount + 1)
        writeLong(header, packet.createdAt)
        writeLong(header, packet.expiresAt)
        header.write(messageId)
        header.write(messageType)
        header.write(content)

        val body = header.toByteArray()
        require(body.size + 4 <= MAX_PACKET_BYTES) { "mesh packet too large" }
        val crc = CRC32().apply { update(body) }.value
        val result = ByteBuffer.allocate(body.size + 4).order(ByteOrder.BIG_ENDIAN)
        result.put(body)
        result.putInt(crc.toInt())
        return result.array()
    }

    fun decode(bytes: ByteArray): MeshPacket? {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_PACKET_BYTES) return null
        return runCatching {
            val expectedCrc = ByteBuffer.wrap(bytes, bytes.size - 4, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int.toLong() and 0xFFFF_FFFFL
            val actualCrc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
            if (expectedCrc != actualCrc) return null

            val buffer = ByteBuffer.wrap(bytes, 0, bytes.size - 4).order(ByteOrder.BIG_ENDIAN)
            if (buffer.get() != MAGIC[0] || buffer.get() != MAGIC[1]) return null
            if (buffer.get() != VERSION) return null
            val packetTypeCode = buffer.get()
            val packetType = MeshPacket.PacketType.entries.firstOrNull { it.code == packetTypeCode }
                ?: return null
            val packetUuid = readUuid(buffer) ?: return null
            val originUuid = readUuid(buffer) ?: return null
            val destinationUuid = readUuid(buffer) ?: return null
            val messageIdLength = buffer.short.toInt() and 0xFFFF
            val messageTypeLength = buffer.short.toInt() and 0xFFFF
            val contentLength = buffer.short.toInt() and 0xFFFF
            val ttl = buffer.get().toInt() and 0xFF
            val hopCount = buffer.get().toInt() and 0xFF
            val encodedDeliveryHop = buffer.get().toInt() and 0xFF
            val createdAt = buffer.long
            val expiresAt = buffer.long

            if (messageIdLength > MAX_TEXT_BYTES || messageTypeLength > 255 || contentLength > MAX_TEXT_BYTES) return null
            val required = messageIdLength + messageTypeLength + contentLength
            if (buffer.remaining() != required) return null

            if (createdAt <= 0L || expiresAt <= createdAt) return null
            if (expiresAt - createdAt > 30 * 60 * 1000L) return null
            if (ttl > MAX_TTL_WIRE || hopCount > MAX_TTL_WIRE) return null
            if (ttl + hopCount != INITIAL_TTL) return null
            if (messageIdLength == 0 || messageTypeLength == 0) return null

            val messageIdBytes = ByteArray(messageIdLength)
            val messageTypeBytes = ByteArray(messageTypeLength)
            val contentBytes = ByteArray(contentLength)
            buffer.get(messageIdBytes)
            buffer.get(messageTypeBytes)
            buffer.get(contentBytes)

            if (originUuid == destinationUuid) return null

            val messageIdText = messageIdBytes.toString(Charsets.UTF_8)
            val messageTypeText = messageTypeBytes.toString(Charsets.UTF_8)
            if (messageIdText.length > 512 || messageTypeText.length > 64) return null
            if (!messageIdText.all { it.isLetterOrDigit() || it in "-_:" }) return null

            // DATA packets use the origin node as the logical message-id namespace.
            // DELIVERY_ACK packets originate at the destination, so their messageId
            // intentionally retains the original sender's namespace.
            if (packetType == MeshPacket.PacketType.DATA) {
                val expectedMessagePrefix = "HMRM-$originUuid::"
                if (!messageIdText.startsWith(expectedMessagePrefix, ignoreCase = true)) return null
            }

            val decodedPacket = MeshPacket(
                packetId = "HMR-$packetUuid",
                messageId = messageIdText,
                originNodeId = "HMR-$originUuid",
                destinationNodeId = "HMR-$destinationUuid",
                createdAt = createdAt,
                expiresAt = expiresAt,
                ttl = ttl,
                hopCount = hopCount,
                packetType = packetType,
                messageType = messageTypeText,
                content = contentBytes.toString(Charsets.UTF_8),
                deliveryHopCount = if (encodedDeliveryHop == 0) null else encodedDeliveryHop - 1
            )
            when (decodedPacket.packetType) {
                MeshPacket.PacketType.DATA -> {
                    if (decodedPacket.messageType !in setOf("NORMAL", "PRIORITY", "EMERGENCY", "LOCATION")) return null
                    if (decodedPacket.content.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return null
                    if (decodedPacket.deliveryHopCount != null) return null
                }
                MeshPacket.PacketType.DELIVERY_ACK -> {
                    if (decodedPacket.messageType != "DELIVERY_ACK") return null
                    if (decodedPacket.content.isNotEmpty()) return null
                    if (decodedPacket.deliveryHopCount == null || decodedPacket.deliveryHopCount !in 0..MAX_TTL_WIRE) return null
                }
            }
            decodedPacket
        }.getOrNull()
    }

    fun newPacketId(): String = "HMR-${UUID.randomUUID()}"

    fun stableDataPacketId(messageId: String): String =
        "HMR-${UUID.nameUUIDFromBytes(messageId.toByteArray(Charsets.UTF_8))}"

    fun stableDeliveryAckPacketId(messageId: String): String =
        "HMR-${UUID.nameUUIDFromBytes((messageId + "::DELIVERY_ACK").toByteArray(Charsets.UTF_8))}"

    fun newTransferId(): Long = UUID.randomUUID().leastSignificantBits

    private fun uuid(nodeId: String): UUID = UUID.fromString(nodeId.removePrefix("HMR-"))

    private fun uuidBytes(value: UUID): ByteArray = ByteBuffer.allocate(16)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    private fun readUuid(buffer: ByteBuffer): UUID? =
        if (buffer.remaining() >= 16) UUID(buffer.long, buffer.long) else null

    private fun writeShort(stream: ByteArrayOutputStream, value: Int) {
        stream.write((value ushr 8) and 0xFF)
        stream.write(value and 0xFF)
    }

    private fun writeLong(stream: ByteArrayOutputStream, value: Long) {
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array().forEach { stream.write(it.toInt()) }
    }
}
