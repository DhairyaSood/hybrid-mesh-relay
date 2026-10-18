package com.hybridmesh.relay.messaging.ble

/** Stable BLE attachment rejection codes carried in the optional ACK detail field. */
enum class BleAttachmentRejection(
    val wireCode: Int,
    val retryable: Boolean
) {
    UNKNOWN_PEER_IDENTITY(1, true),
    SENDER_IDENTITY_MISMATCH(2, true),
    INVALID_MANIFEST(3, false),
    SIZE_LIMIT_EXCEEDED(4, false),
    HOP_LIMIT_EXCEEDED(5, false),
    CLOCK_SKEW(6, false),
    ATTACHMENT_EXPIRED(7, false),
    STORAGE_UNAVAILABLE(8, true),
    ATTACHMENT_METADATA_CONFLICT(9, false),
    RECEIVE_STATE_MISSING(10, true),
    RECEIVE_SOURCE_MISMATCH(11, true),
    CHUNK_REJECTED(12, true),
    FINISH_REJECTED(13, true),
    INVALID_FRAME(14, false),
    RECEIVER_UNAVAILABLE(15, true),
    RECEIVER_BUSY(16, true),
    RECEIVER_TIMEOUT(17, true),
    RECEIVER_ERROR(18, true),
    ASSEMBLY_LIMIT(19, true),
    ASSEMBLY_INVALID(20, true),
    INVALID_HOP_IDENTITY(21, false),
    ATTACHMENT_PROTOCOL_MISMATCH(22, false);

    companion object {
        fun fromWireCode(code: Int?): BleAttachmentRejection? =
            values().firstOrNull { it.wireCode == code }
    }
}

sealed interface BleAttachmentReceiveResult {
    data class Accepted(val offset: Long) : BleAttachmentReceiveResult
    data class Rejected(val reason: BleAttachmentRejection) : BleAttachmentReceiveResult
}
