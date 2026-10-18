package com.hybridmesh.relay.messaging.ble

import com.hybridmesh.relay.messaging.model.GattTransportSnapshot

/** Result of one BLE attachment attempt, including the last useful transport snapshot. */
data class BleAttachmentSendResult(
    val succeeded: Boolean,
    val failureCode: String? = null,
    val stage: String,
    val acknowledgedBytes: Long = 0L,
    val estimatedGattWrites: Long? = null,
    val snapshot: GattTransportSnapshot
) {
    companion object {
        fun failed(
            code: String,
            stage: String,
            messageId: String,
            acknowledgedBytes: Long = 0L,
            estimatedGattWrites: Long? = null,
            snapshot: GattTransportSnapshot = GattTransportSnapshot(messageId = messageId)
        ) = BleAttachmentSendResult(
            succeeded = false,
            failureCode = code,
            stage = stage,
            acknowledgedBytes = acknowledgedBytes,
            estimatedGattWrites = estimatedGattWrites,
            snapshot = snapshot
        )
    }
}
