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
            snapshot: GattTransportSnapshot = GattTransportSnapshot(messageId = messageId)
        ) = BleAttachmentSendResult(
            succeeded = false,
            failureCode = code,
            stage = stage,
            acknowledgedBytes = acknowledgedBytes,
            estimatedGattWrites = estimatedGattWrites,
            retryable = retryable,
            snapshot = snapshot
        )
    }
}
