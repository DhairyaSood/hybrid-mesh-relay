package com.hybridmesh.relay.messaging.attachment

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small application frames carried by the existing reliable BLE/GATT session. */
object BleAttachmentFrameCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x59, 0x41, 0x31) // NYA1
    private const val VERSION: Byte = 1
    private const val BEGIN: Byte = 1
    private const val CHUNK: Byte = 2
    private const val FINISH: Byte = 3
    const val MAX_CHUNK_BYTES = 32 * 1024
    const val MAX_FRAME_BYTES = MAX_CHUNK_BYTES + 1024

    sealed interface Frame {
        val transferId: Long
        data class Begin(override val transferId: Long, val manifest: AttachmentTransferManifest) : Frame
        data class Chunk(override val transferId: Long, val messageId: String, val offset: Long, val bytes: ByteArray) : Frame
        data class Finish(override val transferId: Long, val messageId: String) : Frame
    }

    fun isFrame(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size &&
        MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encodeBegin(transferId: Long, manifest: AttachmentTransferManifest): ByteArray {
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
            }
        }.toByteArray()
        return encodeHeader(BEGIN, transferId, body)
    }

    fun encodeChunk(transferId: Long, messageId: String, offset: Long, bytes: ByteArray): ByteArray {
        require(messageId.isNotBlank() && messageId.length <= 160)
        require(offset >= 0L && bytes.isNotEmpty() && bytes.size <= MAX_CHUNK_BYTES)
        val id = messageId.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + 1 + 1 + 8 + 8 + 2 + id.size + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(MAGIC); put(VERSION); put(CHUNK); putLong(transferId); putLong(offset)
                putShort(id.size.toShort()); put(id); put(bytes)
            }.array()
    }

    fun encodeFinish(transferId: Long, messageId: String): ByteArray {
        val id = messageId.toByteArray(Charsets.UTF_8)
        require(id.isNotEmpty() && id.size <= 160)
        return ByteBuffer.allocate(4 + 1 + 1 + 8 + 2 + id.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply { put(MAGIC); put(VERSION); put(FINISH); putLong(transferId); putShort(id.size.toShort()); put(id) }
            .array()
    }

    fun decode(bytes: ByteArray): Frame? {
        if (!isFrame(bytes) || bytes.size > MAX_FRAME_BYTES) return null
        return runCatching {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            input.position(MAGIC.size)
            if (input.get() != VERSION) return null
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
                    if (input.remaining() != 12) return null
                    val createdAt = input.long
                    val hops = input.int
                    if (input.hasRemaining()) return null
                    Frame.Begin(transferId, AttachmentTransferManifest(messageId, sender, recipient, mime, name, size, hash, createdAt, hops))
                }
                CHUNK -> {
                    if (input.remaining() < 10) return null
                    val offset = input.long
                    val idLength = input.short.toInt() and 0xFFFF
                    if (idLength !in 1..160 || input.remaining() <= idLength) return null
                    val id = ByteArray(idLength).also { input.get(it) }.toString(Charsets.UTF_8)
                    val chunk = ByteArray(input.remaining()).also { input.get(it) }
                    if (offset < 0L || chunk.size > MAX_CHUNK_BYTES) return null
                    Frame.Chunk(transferId, id, offset, chunk)
                }
                FINISH -> {
                    val id = input.readUtf8(160)
                    if (input.hasRemaining()) return null
                    Frame.Finish(transferId, id)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun encodeHeader(type: Byte, transferId: Long, body: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 1 + 1 + 8 + body.size).order(ByteOrder.BIG_ENDIAN)
            .apply { put(MAGIC); put(VERSION); put(type); putLong(transferId); put(body) }.array()

    private fun DataOutputStream.writeUtf8(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maxBytes)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.readUtf8(maxBytes: Int): String {
        if (remaining() < 2) throw IllegalArgumentException("Truncated text field")
        val length = short.toInt() and 0xFFFF
        if (length !in 1..maxBytes || remaining() < length) throw IllegalArgumentException("Invalid text field")
        return ByteArray(length).also { get(it) }.toString(Charsets.UTF_8)
    }
}
