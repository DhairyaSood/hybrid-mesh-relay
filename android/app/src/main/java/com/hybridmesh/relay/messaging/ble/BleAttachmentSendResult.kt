package com.hybridmesh.relay.messaging.ble

import com.hybridmesh.relay.messaging.model.GattTransportSnapshot

/** Result of one BLE attachment attempt, including the last useful transport snapshot. */
data class BleAttachmentSendResult(
    val succeeded: Boolean,
    val failureCode: String? = null,
    val stage: String,
    val acknowledgedBytes: Long = 0L,
    val estimatedGattWrites: Long? = null,
    val retryable: Boolean = true,
    val discoveryAttempts: Int = 1,
    val firstAttemptFailureCode: String? = null,
    val firstAttemptDiscoveryOutcome: String? = null,
    val firstAttemptDiscoveryStatus: Int? = null,
    val firstAttemptDiscoveredServices: String? = null,
    val firstAttemptMissingAttributes: String? = null,
    val snapshot: GattTransportSnapshot
) {
    companion object {
        fun failed(
            code: String,
            stage: String,
            messageId: String,
            acknowledgedBytes: Long = 0L,
            estimatedGattWrites: Long? = null,
            retryable: Boolean = true,
            discoveryAttempts: Int = 1,
            firstAttemptFailureCode: String? = null,
            firstAttemptDiscoveryOutcome: String? = null,
            firstAttemptDiscoveryStatus: Int? = null,
            firstAttemptDiscoveredServices: String? = null,
            firstAttemptMissingAttributes: String? = null,
            snapshot: GattTransportSnapshot = GattTransportSnapshot(messageId = messageId)
        ) = BleAttachmentSendResult(
            succeeded = false,
            failureCode = code,
            stage = stage,
            acknowledgedBytes = acknowledgedBytes,
            estimatedGattWrites = estimatedGattWrites,
            retryable = retryable,
            discoveryAttempts = discoveryAttempts,
            firstAttemptFailureCode = firstAttemptFailureCode,
            firstAttemptDiscoveryOutcome = firstAttemptDiscoveryOutcome,
            firstAttemptDiscoveryStatus = firstAttemptDiscoveryStatus,
            firstAttemptDiscoveredServices = firstAttemptDiscoveredServices,
            firstAttemptMissingAttributes = firstAttemptMissingAttributes,
            snapshot = snapshot
        )
    }
}
