package com.hybridmesh.relay.messaging.attachment

import org.json.JSONObject

/** Small control-plane description carried as an ordinary mesh message. */
data class AttachmentDescriptor(
    val messageId: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String
) {
    fun encode(): String = JSONObject()
        .put("v", VERSION)
        .put("id", messageId)
        .put("mime", mimeType)
        .put("name", displayName)
        .put("size", sizeBytes)
        .put("sha256", sha256)
        .toString()

    companion object {
        const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
        private const val VERSION = 1

        fun decode(value: String): AttachmentDescriptor? = runCatching {
            val json = JSONObject(value)
            if (json.optInt("v") != VERSION) return null
            val id = json.optString("id").takeIf { it.isNotBlank() && it.length <= 160 } ?: return null
            val mime = json.optString("mime").lowercase()
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
            val name = json.optString("name").takeIf { it.isNotBlank() && it.length <= 180 } ?: return null
            val size = json.optLong("size", -1L)
            if (size !in 1..MAX_ATTACHMENT_BYTES) return null
            val hash = json.optString("sha256").lowercase()
            if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
            AttachmentDescriptor(id, mime, name, size, hash)
        }.getOrNull()
    }
}

object AttachmentTransferStatus {
    const val QUEUED = "QUEUED"
    const val WAITING_WIFI = "WAITING_WIFI"
    const val SENDING = "SENDING"
    const val RECEIVING = "RECEIVING"
    const val RELAY_QUEUED = "RELAY_QUEUED"
    const val RELAYED = "RELAYED"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
}
