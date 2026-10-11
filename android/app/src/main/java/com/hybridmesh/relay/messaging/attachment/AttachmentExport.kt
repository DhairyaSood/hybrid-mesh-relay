package com.hybridmesh.relay.messaging.attachment

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

data class AttachmentExportSource(
    val file: File,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long
)

sealed interface AttachmentExportResult {
    data class Success(val displayName: String) : AttachmentExportResult
    data class Failure(val message: String) : AttachmentExportResult
}

object AttachmentExport {
    private val exportDirectory = "${Environment.DIRECTORY_DOWNLOADS}/Neyra"
    private const val CHUNK_BYTES = 64 * 1024

    fun suggestedFileName(source: AttachmentExportSource): String =
        sanitizeName(source.displayName, source.mimeType)

    fun exportToDownloads(
        context: Context,
        source: AttachmentExportSource
    ): AttachmentExportResult {
        val validationError = validate(source)
        if (validationError != null) return AttachmentExportResult.Failure(validationError)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AttachmentExportResult.Failure("Use the save dialog on this Android version.")
        }

        return try {
            val resolver = context.applicationContext.contentResolver
            val displayName = uniqueDisplayName(resolver, source)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, source.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, exportDirectory)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return AttachmentExportResult.Failure("Could not create a file in Downloads.")
            try {
                copyToUri(resolver, source, uri)
                val updated = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                if (updated != 1) throw IOException("Could not finalize the saved attachment.")
                AttachmentExportResult.Success(displayName)
            } catch (cancellation: CancellationException) {
                resolver.delete(uri, null, null)
                throw cancellation
            } catch (failure: Exception) {
                resolver.delete(uri, null, null)
                throw failure
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AttachmentExportResult.Failure(
                failure.message ?: "Could not save the attachment."
            )
        }
    }

    fun exportToUri(
        context: Context,
        source: AttachmentExportSource,
        destination: Uri
    ): AttachmentExportResult {
        val validationError = validate(source)
        if (validationError != null) return AttachmentExportResult.Failure(validationError)

        return try {
            val resolver = context.applicationContext.contentResolver
            copyToUri(resolver, source, destination)
            AttachmentExportResult.Success(source.displayName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AttachmentExportResult.Failure(
                failure.message ?: "Could not save the attachment."
            )
        }
    }

    private fun copyToUri(
        resolver: ContentResolver,
        source: AttachmentExportSource,
        destination: Uri
    ) {
        val input = source.file.inputStream()
        val output = resolver.openOutputStream(destination)
            ?: throw IOException("Could not open the destination file.")
        input.use { sourceStream ->
            output.use { destinationStream ->
                val buffer = ByteArray(CHUNK_BYTES)
                var copied = 0L
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    destinationStream.write(buffer, 0, count)
                    copied += count
                }
                destinationStream.flush()
                if (copied != source.sizeBytes) {
                    throw IOException("The attachment changed while it was being saved.")
                }
            }
        }
    }

    private fun validate(source: AttachmentExportSource): String? {
        if (!source.mimeType.startsWith("image/") && !source.mimeType.startsWith("video/")) {
            return "Only photos and videos can be saved."
        }
        if (!source.file.isFile || !source.file.canRead()) {
            return "This attachment is not available on this device."
        }
        if (source.sizeBytes <= 0L || source.file.length() != source.sizeBytes) {
            return "This attachment is not fully available yet."
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun uniqueDisplayName(
        resolver: ContentResolver,
        source: AttachmentExportSource
    ): String {
        val baseName = suggestedFileName(source)
        var candidate = baseName
        var suffix = 1
        while (displayNameExists(resolver, candidate)) {
            candidate = addSuffix(baseName, suffix++)
        }
        return candidate
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayNameExists(resolver: ContentResolver, displayName: String): Boolean {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?"
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(exportDirectory, displayName),
            null
        )?.use { it.moveToFirst() } == true
    }

    private fun addSuffix(name: String, suffix: Int): String {
        val extensionIndex = name.lastIndexOf('.')
        return if (extensionIndex > 0) {
            "${name.substring(0, extensionIndex)} ($suffix)${name.substring(extensionIndex)}"
        } else {
            "$name ($suffix)"
        }
    }

    private fun sanitizeName(name: String, mimeType: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in ". _-()" }
            .trim()
            .take(100)
        if (cleaned.isNotBlank()) return cleaned
        return if (mimeType.startsWith("video/")) "video" else "image"
    }
}
