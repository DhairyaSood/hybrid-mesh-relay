package com.hybridmesh.relay.messaging.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Compact BLE wire protocol. Every DATA frame is self-contained at the transport
 * layer and can fit inside the default ATT payload (20 bytes at MTU 23).
 *
 * DATA frame:
 *   magic(2) version(1) type(1) transferId(8) chunkIndex(2) chunkCount(2) payload(N)
 *
 * The payload stream is the serialized MessageEnvelope. Only the first-level
 * transport header is repeated for each BLE write.
 *
 * ACK frame:
 *   magic(2) version(1) type(1) transferId(8) accepted(1)
 */
object GattPacketCodec {
    private const val MAGIC: Short = 0x484D // "HM"
    private const val VERSION: Byte = 1
    private const val TYPE_DATA: Byte = 1
    private const val TYPE_ACK: Byte = 2

    const val DATA_HEADER_BYTES = 16
    const val ACK_BYTES = 13
    const val MIN_ATT_MTU = 23
    const val ATT_PROTOCOL_OVERHEAD = 3
    const val ENVELOPE_VERSION: Byte = 1
    const val MAX_CHUNKS = 65_535

    data class DataFrame(
        val transferId: Long,
        val chunkIndex: Int,
        val chunkCount: Int,
        val payload: ByteArray
    )

    data class AckFrame(
        val transferId: Long,
        val accepted: Boolean
    )

    data class MessageEnvelope(
        val senderNodeId: String,
        val recipientNodeId: String,
        val messageType: Byte,
        val createdAt: Long,
        val content: String
    )

    fun safePayloadBytes(mtu: Int): Int =
        (mtu.coerceAtLeast(MIN_ATT_MTU) - ATT_PROTOCOL_OVERHEAD - DATA_HEADER_BYTES)
            .coerceAtLeast(1)

    fun encodeMessageEnvelope(
        senderNodeId: String,
        recipientNodeId: String,
        messageType: Byte,
        createdAt: Long,
        content: String
    ): ByteArray {
        val sender = nodeUuid(senderNodeId)
        val recipient = nodeUuid(recipientNodeId)
        val contentBytes = content.toByteArray(Charsets.UTF_8)

        require(contentBytes.size.toLong() <= 0xFFFF_FFFFL) {
            "Message content is too large for the wire format"
        }

        return ByteBuffer.allocate(1 + 16 + 16 + 1 + 8 + 4 + contentBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(ENVELOPE_VERSION)
                putUuid(sender)
                putUuid(recipient)
                put(messageType)
                putLong(createdAt)
                putInt(contentBytes.size)
                put(contentBytes)
            }
            .array()
    }

    fun decodeMessageEnvelope(bytes: ByteArray): MessageEnvelope? {
        if (bytes.size < 46) return null

        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.get() != ENVELOPE_VERSION) return null

            val sender = readUuid(buffer) ?: return null
            val recipient = readUuid(buffer) ?: return null
            val messageType = buffer.get()
            val createdAt = buffer.long
            val contentLength = buffer.int.toLong() and 0xFFFF_FFFFL

            if (contentLength > buffer.remaining()) return null

            val contentBytes = ByteArray(contentLength.toInt())
            buffer.get(contentBytes)

            MessageEnvelope(
                senderNodeId = "HMR-$sender",
                recipientNodeId = "HMR-$recipient",
                messageType = messageType,
                createdAt = createdAt,
                content = contentBytes.toString(Charsets.UTF_8)
            )
        }.getOrNull()
    }

    fun encodeData(frame: DataFrame): ByteArray {
        require(frame.chunkCount in 1..MAX_CHUNKS)
        require(frame.chunkIndex in 0 until frame.chunkCount)
        require(frame.payload.size <= 0xFFFF)

        return ByteBuffer.allocate(DATA_HEADER_BYTES + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(MAGIC)
                put(VERSION)
                put(TYPE_DATA)
                putLong(frame.transferId)
                putShort(frame.chunkIndex.toShort())
                putShort(frame.chunkCount.toShort())
                put(frame.payload)
            }
            .array()
    }

    fun decode(bytes: ByteArray): Any? {
        if (bytes.size < 4) return null

        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.short != MAGIC) return null
            if (buffer.get() != VERSION) return null

            when (buffer.get()) {
                TYPE_DATA -> decodeData(buffer)
                TYPE_ACK -> decodeAck(buffer)
                else -> null
            }
        }.getOrNull()
    }

    fun encodeAck(transferId: Long, accepted: Boolean): ByteArray =
        ByteBuffer.allocate(ACK_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(MAGIC)
                put(VERSION)
                put(TYPE_ACK)
                putLong(transferId)
                put(if (accepted) 1 else 0)
            }
            .array()

    /**
     * Preserves the logical message-id shape used by Room while making the
     * transport token recoverable as a stable 64-bit identifier.
     */
    fun messageId(originNodeId: String, transferId: Long): String =
        "HMRM-${originNodeId.removePrefix("HMR-").lowercase()}::${transferId.toULong().toString(16).padStart(16, '0')}"

    fun tokenFromMessageId(messageId: String): Long? {
        val value = messageId.substringAfterLast("::", "").trim()
        if (value.isBlank()) return null
        runCatching {
            return java.lang.Long.parseUnsignedLong(value, 16)
        }
        runCatching {
            return UUID.fromString(value).leastSignificantBits
        }
        return null
    }

    fun transferIdFromUuid(uuid: UUID): Long = uuid.leastSignificantBits

    private fun decodeData(buffer: ByteBuffer): DataFrame? {
        if (buffer.remaining() < DATA_HEADER_BYTES - 4) return null

        val transferId = buffer.long
        val chunkIndex = buffer.short.toInt() and 0xFFFF
        val chunkCount = buffer.short.toInt() and 0xFFFF

        if (chunkCount <= 0 || chunkIndex >= chunkCount) return null
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        return DataFrame(
            transferId = transferId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            payload = payload
        )
    }

    private fun decodeAck(buffer: ByteBuffer): AckFrame? {
        if (buffer.remaining() != 9) return null
        val transferId = buffer.long
        val accepted = buffer.get().toInt() == 1
        return AckFrame(transferId, accepted)
    }

    private fun nodeUuid(nodeId: String): UUID =
        UUID.fromString(nodeId.removePrefix("HMR-"))

    private fun ByteBuffer.putUuid(uuid: UUID) {
        putLong(uuid.mostSignificantBits)
        putLong(uuid.leastSignificantBits)
    }

    private fun readUuid(buffer: ByteBuffer): UUID? =
        if (buffer.remaining() >= 16) UUID(buffer.long, buffer.long) else null
}
