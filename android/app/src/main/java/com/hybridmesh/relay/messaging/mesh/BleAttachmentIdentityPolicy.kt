package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import com.hybridmesh.relay.messaging.ble.BleAttachmentRejection

/** Pure identity checks shared by BLE attachment ingress and its regression tests. */
internal object BleAttachmentIdentityPolicy {
    data class Resolution(
        val nodeId: String? = null,
        val rejection: BleAttachmentRejection? = null
    )

    fun resolveBegin(
        protocolVersion: Int,
        claimedHopNodeId: String?,
        observedNodeId: String?,
        boundNodeId: String?,
        boundProtocolVersion: Int?
    ): Resolution {
        if (boundProtocolVersion != null && boundProtocolVersion != protocolVersion) {
            return Resolution(rejection = BleAttachmentRejection.ATTACHMENT_PROTOCOL_MISMATCH)
        }

        val sourceNodeId = when (protocolVersion) {
            BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION -> {
                if (claimedHopNodeId.isNullOrBlank() || !NodeIdGenerator.isValid(claimedHopNodeId)) {
                    return Resolution(rejection = BleAttachmentRejection.INVALID_HOP_IDENTITY)
                }
                claimedHopNodeId
            }
            BleAttachmentFrameCodec.LEGACY_PROTOCOL_VERSION -> boundNodeId ?: observedNodeId
                ?: return Resolution(rejection = BleAttachmentRejection.UNKNOWN_PEER_IDENTITY)
            else -> return Resolution(rejection = BleAttachmentRejection.INVALID_FRAME)
        }

        if (observedNodeId != null && !observedNodeId.equals(sourceNodeId, ignoreCase = true)) {
            return Resolution(rejection = BleAttachmentRejection.SENDER_IDENTITY_MISMATCH)
        }
        if (boundNodeId != null && !boundNodeId.equals(sourceNodeId, ignoreCase = true)) {
            return Resolution(rejection = BleAttachmentRejection.SENDER_IDENTITY_MISMATCH)
        }
        return Resolution(nodeId = sourceNodeId)
    }

    fun resolveContinuation(
        protocolVersion: Int,
        observedNodeId: String?,
        boundNodeId: String?,
        boundProtocolVersion: Int?
    ): Resolution {
        if (boundNodeId == null || boundProtocolVersion == null) {
            return Resolution(rejection = BleAttachmentRejection.UNKNOWN_PEER_IDENTITY)
        }
        if (boundProtocolVersion != protocolVersion) {
            return Resolution(rejection = BleAttachmentRejection.ATTACHMENT_PROTOCOL_MISMATCH)
        }
        if (observedNodeId != null && !observedNodeId.equals(boundNodeId, ignoreCase = true)) {
            return Resolution(rejection = BleAttachmentRejection.SENDER_IDENTITY_MISMATCH)
        }
        return Resolution(nodeId = boundNodeId)
    }
}
