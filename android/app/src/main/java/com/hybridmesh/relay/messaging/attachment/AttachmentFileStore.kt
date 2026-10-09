package com.hybridmesh.relay.messaging.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

data class ImportedAttachment(
    val file: File,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String
)

/** Bounded streaming file I/O for outgoing and incoming private attachment files. */
object AttachmentFileStore {
    private const val CHUNK_BYTES = 64 * 1024

    fun importPickedFile(context: Context, uri: Uri): ImportedAttachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("Could not determine whether this is an image or video.")
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            throw IllegalArgumentException("Choose an image or video.")
        }
        val suppliedName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val displayName = sanitizeName(suppliedName?.takeIf { it.isNotBlank() } ?: defaultName(mime))
        val dir = attachmentDirectory(context)
        val temp = File(dir, "${UUID.randomUUID()}.part")
        val target = File(dir, "${UUID.randomUUID()}.media")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open the selected file.")
            input.use { source ->
                temp.outputStream().buffered(CHUNK_BYTES).use { output ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > AttachmentDescriptor.MAX_ATTACHMENT_BYTES) {
                            throw IllegalArgumentException("The file is larger than 10 MB.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total == 0L) throw IllegalArgumentException("The selected file is empty.")
            if (!temp.renameTo(target)) throw IllegalStateException("Could not save the selected file.")
            return ImportedAttachment(target, mime, displayName, total, digest.digest().toHex())
        } catch (failure: Throwable) {
            temp.delete()
            target.delete()
            throw failure
        }
    }

    fun receivePart(context: Context, messageId: String): File =
        File(attachmentDirectory(context), "${stableFileKey(messageId)}.part")

    fun receivedFile(context: Context, messageId: String): File =
        File(attachmentDirectory(context), "${stableFileKey(messageId)}.media")

    fun deleteReceivedFiles(context: Context, messageId: String) {
        receivePart(context, messageId).delete()
        receivedFile(context, messageId).delete()
    }

    fun prepareReceive(context: Context, manifest: AttachmentTransferManifest): Long? {
        val part = receivePart(context, manifest.messageId)
        val completed = receivedFile(context, manifest.messageId)
        if (completed.isFile && completed.length() == manifest.sizeBytes && sha256(completed) == manifest.sha256) {
            return manifest.sizeBytes
        }
        if (completed.exists()) completed.delete()
        if (part.exists() && part.length() > manifest.sizeBytes) {
            if (!part.delete()) return null
        }
        val existingBytes = part.takeIf { it.exists() }?.length() ?: 0L
        val available = runCatching { StatFs(part.parentFile!!.absolutePath).availableBytes }.getOrDefault(0L)
        if (available < manifest.sizeBytes - existingBytes) return null
        if (!part.exists()) part.createNewFile()
        return part.length().takeIf { it <= manifest.sizeBytes }
    }

    /** Must be called with the app context used to prepare this message's part file. */
    fun appendChunk(context: Context, messageId: String, expectedOffset: Long, bytes: ByteArray, totalBytes: Long): Long? {
        if (bytes.isEmpty() || bytes.size > CHUNK_BYTES || expectedOffset < 0L || expectedOffset + bytes.size > totalBytes) return null
        val part = receivePart(context, messageId)
        return runCatching {
            RandomAccessFile(part, "rw").use { output ->
                if (output.length() != expectedOffset) return null
                output.seek(expectedOffset)
                output.write(bytes)
                output.filePointer
            }
        }.getOrNull()
    }

    fun finishReceive(context: Context, manifest: AttachmentTransferManifest): File? {
        val finalFile = receivedFile(context, manifest.messageId)
        if (finalFile.isFile && finalFile.length() == manifest.sizeBytes && sha256(finalFile) == manifest.sha256) return finalFile
        val part = receivePart(context, manifest.messageId)
        if (!part.isFile || part.length() != manifest.sizeBytes) return null
        if (sha256(part) != manifest.sha256) {
            part.delete()
            return null
        }
        if (finalFile.exists()) finalFile.delete()
        if (!part.renameTo(finalFile)) return null
        return finalFile
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(CHUNK_BYTES).use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun attachmentDirectory(context: Context): File =
        File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    private fun stableFileKey(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in ". _-()" }
            .trim().take(100).ifBlank { "attachment" }

    private fun defaultName(mime: String): String = if (mime.startsWith("video/")) "video" else "image"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

}
