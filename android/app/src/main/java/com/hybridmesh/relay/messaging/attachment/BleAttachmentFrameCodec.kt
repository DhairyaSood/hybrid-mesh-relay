package com.hybridmesh.relay.messaging.attachment

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Small application frames carried by the existing reliable BLE/GATT session. */
object BleAttachmentFrameCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x59, 0x41, 0x31) // NYA1
    const val LEGACY_PROTOCOL_VERSION = 1
    const val HOP_IDENTITY_PROTOCOL_VERSION = 2
    private const val BEGIN: Byte = 1
    private const val CHUNK: Byte = 2
    private const val FINISH: Byte = 3
    const val MAX_CHUNK_BYTES = 32 * 1024
    const val MAX_FRAME_BYTES = MAX_CHUNK_BYTES + 1024

    sealed interface Frame {
        val transferId: Long
        val protocolVersion: Int
        data class Begin(
            override val transferId: Long,
            override val protocolVersion: Int,
            val manifest: AttachmentTransferManifest,
            val immediateHopSenderNodeId: String?
        ) : Frame
        data class Chunk(
            override val transferId: Long,
            override val protocolVersion: Int,
            val messageId: String,
            val offset: Long,
            val bytes: ByteArray
        ) : Frame
        data class Finish(
            override val transferId: Long,
            override val protocolVersion: Int,
            val messageId: String
        ) : Frame
    }

    fun isFrame(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size &&
        MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encodeBegin(
        transferId: Long,
        manifest: AttachmentTransferManifest,
        protocolVersion: Int = LEGACY_PROTOCOL_VERSION,
        immediateHopSenderNodeId: String? = null
    ): ByteArray {
        require(protocolVersion == LEGACY_PROTOCOL_VERSION ||
            protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION)
        if (protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION) {
            require(!immediateHopSenderNodeId.isNullOrBlank() && immediateHopSenderNodeId.length <= 64)
        } else {
            require(immediateHopSenderNodeId == null)
        }
        val body = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeUtf8(manifest.messageId, 160)
                data.writeUtf8(manifest.senderNodeId, 64)
                data.writeUtf8(manifest.recipientNodeId, 64)
                data.writeUtf8(manifest.mimeType, AttachmentTransferManifest.MAX_MIME_TYPE_UTF8_BYTES)
                data.writeUtf8(manifest.displayName, AttachmentTransferManifest.MAX_DISPLAY_NAME_UTF8_BYTES)
                data.writeLong(manifest.sizeBytes)
                data.writeUtf8(manifest.sha256, 64)
                data.writeLong(manifest.createdAt)
                data.writeInt(manifest.hopCount)
                if (protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION) {
                    data.writeUtf8(immediateHopSenderNodeId!!, 64)
                }
            }
        }.toByteArray()
        return encodeHeader(BEGIN, transferId, protocolVersion, body)
    }

    fun encodeChunk(
        transferId: Long,
        messageId: String,
        offset: Long,
        bytes: ByteArray,
        protocolVersion: Int = LEGACY_PROTOCOL_VERSION
    ): ByteArray {
        require(messageId.isNotBlank() && messageId.length <= 160)
        require(offset >= 0L && bytes.isNotEmpty() && bytes.size <= MAX_CHUNK_BYTES)
        require(protocolVersion == LEGACY_PROTOCOL_VERSION ||
            protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION)
        val id = encodeUtf8(messageId)
        require(id.size in 1..160) { "messageId is too large in UTF-8 bytes" }
        return ByteBuffer.allocate(4 + 1 + 1 + 8 + 8 + 2 + id.size + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(MAGIC); put(protocolVersion.toByte()); put(CHUNK); putLong(transferId); putLong(offset)
                putShort(id.size.toShort()); put(id); put(bytes)
            }.array()
    }

    fun encodeFinish(
        transferId: Long,
        messageId: String,
        protocolVersion: Int = LEGACY_PROTOCOL_VERSION
    ): ByteArray {
        val id = encodeUtf8(messageId)
        require(id.isNotEmpty() && id.size <= 160)
        require(protocolVersion == LEGACY_PROTOCOL_VERSION ||
            protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION)
        return ByteBuffer.allocate(4 + 1 + 1 + 8 + 2 + id.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply { put(MAGIC); put(protocolVersion.toByte()); put(FINISH); putLong(transferId); putShort(id.size.toShort()); put(id) }
            .array()
    }

    fun decode(bytes: ByteArray): Frame? {
        if (!isFrame(bytes) || bytes.size > MAX_FRAME_BYTES) return null
        return runCatching {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            input.position(MAGIC.size)
            val protocolVersion = input.get().toInt() and 0xFF
            if (protocolVersion != LEGACY_PROTOCOL_VERSION &&
                protocolVersion != HOP_IDENTITY_PROTOCOL_VERSION) return null
            val type = input.get()
            val transferId = input.long
            when (type) {
                BEGIN -> {
                    val messageId = input.readUtf8(160)
                    val sender = input.readUtf8(64)
                    val recipient = input.readUtf8(64)
                    val mime = input.readUtf8(AttachmentTransferManifest.MAX_MIME_TYPE_UTF8_BYTES)
                    val name = input.readUtf8(AttachmentTransferManifest.MAX_DISPLAY_NAME_UTF8_BYTES)
                    if (input.remaining() < 8) return null
                    val size = input.long
                    val hash = input.readUtf8(64)
                    if (input.remaining() < 12) return null
                    val createdAt = input.long
                    val hops = input.int
                    val immediateHopSenderNodeId = if (protocolVersion == HOP_IDENTITY_PROTOCOL_VERSION) {
                        input.readUtf8(64)
                    } else null
                    if (input.hasRemaining()) return null
                    Frame.Begin(
                        transferId,
                        protocolVersion,
                        AttachmentTransferManifest(messageId, sender, recipient, mime, name, size, hash, createdAt, hops),
                        immediateHopSenderNodeId
                    )
                }
                CHUNK -> {
                    if (input.remaining() < 10) return null
                    val offset = input.long
                    val idLength = input.short.toInt() and 0xFFFF
                    if (idLength !in 1..160 || input.remaining() <= idLength) return null
                    val id = decodeUtf8(ByteArray(idLength).also { input.get(it) })
                    val chunk = ByteArray(input.remaining()).also { input.get(it) }
                    if (offset < 0L || chunk.size > MAX_CHUNK_BYTES) return null
                    Frame.Chunk(transferId, protocolVersion, id, offset, chunk)
                }
                FINISH -> {
                    val id = input.readUtf8(160)
                    if (input.hasRemaining()) return null
                    Frame.Finish(transferId, protocolVersion, id)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun encodeHeader(type: Byte, transferId: Long, protocolVersion: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 1 + 1 + 8 + body.size).order(ByteOrder.BIG_ENDIAN)
            .apply { put(MAGIC); put(protocolVersion.toByte()); put(type); putLong(transferId); put(body) }.array()

    private fun DataOutputStream.writeUtf8(value: String, maxBytes: Int) {
        val bytes = encodeUtf8(value)
        require(bytes.size in 1..maxBytes)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.readUtf8(maxBytes: Int): String {
        if (remaining() < 2) throw IllegalArgumentException("Truncated text field")
        val length = short.toInt() and 0xFFFF
        if (length !in 1..maxBytes || remaining() < length) throw IllegalArgumentException("Invalid text field")
        val bytes = ByteArray(length).also { get(it) }
        return decodeUtf8(bytes)
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun encodeUtf8(value: String): ByteArray {
        val encoded = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Text contains malformed UTF-16", failure)
        }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
}
