package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import com.hybridmesh.relay.messaging.ble.BleAttachmentRejection
import com.hybridmesh.relay.messaging.mesh.BleAttachmentIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleAttachmentIdentityPolicyTest {
    private val originNodeId = "HMR-11111111-1111-1111-1111-111111111111"
    private val relayNodeId = "HMR-22222222-2222-2222-2222-222222222222"

    @Test
    fun v2BeginResolvesHopWhenReceiverMissedDiscovery() {
        val result = BleAttachmentIdentityPolicy.resolveBegin(
            protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION,
            claimedHopNodeId = relayNodeId,
            observedNodeId = null,
            boundNodeId = null,
            boundProtocolVersion = null
        )

        assertEquals(relayNodeId, result.nodeId)
        assertNull(result.rejection)
    }

    @Test
    fun observedIdentityMismatchIsRejected() {
        val result = BleAttachmentIdentityPolicy.resolveBegin(
            protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION,
            claimedHopNodeId = relayNodeId,
            observedNodeId = originNodeId,
            boundNodeId = null,
            boundProtocolVersion = null
        )

        assertEquals(BleAttachmentRejection.SENDER_IDENTITY_MISMATCH, result.rejection)
    }

    @Test
    fun legacyBeginStillRequiresDiscoveryOrExistingBinding() {
        val unresolved = BleAttachmentIdentityPolicy.resolveBegin(
            protocolVersion = BleAttachmentFrameCodec.LEGACY_PROTOCOL_VERSION,
            claimedHopNodeId = null,
            observedNodeId = null,
            boundNodeId = null,
            boundProtocolVersion = null
        )
        val resolved = BleAttachmentIdentityPolicy.resolveBegin(
            protocolVersion = BleAttachmentFrameCodec.LEGACY_PROTOCOL_VERSION,
            claimedHopNodeId = null,
            observedNodeId = originNodeId,
            boundNodeId = null,
            boundProtocolVersion = null
        )

        assertEquals(BleAttachmentRejection.UNKNOWN_PEER_IDENTITY, unresolved.rejection)
        assertEquals(originNodeId, resolved.nodeId)
    }

    @Test
    fun continuationRequiresSameTransferBindingAndFrameVersion() {
        val unresolved = BleAttachmentIdentityPolicy.resolveContinuation(
            protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION,
            observedNodeId = null,
            boundNodeId = null,
            boundProtocolVersion = null
        )
        val mismatched = BleAttachmentIdentityPolicy.resolveContinuation(
            protocolVersion = BleAttachmentFrameCodec.LEGACY_PROTOCOL_VERSION,
            observedNodeId = null,
            boundNodeId = relayNodeId,
            boundProtocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION
        )
        val resolved = BleAttachmentIdentityPolicy.resolveContinuation(
            protocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION,
            observedNodeId = null,
            boundNodeId = relayNodeId,
            boundProtocolVersion = BleAttachmentFrameCodec.HOP_IDENTITY_PROTOCOL_VERSION
        )

        assertEquals(BleAttachmentRejection.UNKNOWN_PEER_IDENTITY, unresolved.rejection)
        assertEquals(BleAttachmentRejection.ATTACHMENT_PROTOCOL_MISMATCH, mismatched.rejection)
        assertEquals(relayNodeId, resolved.nodeId)
    }
}
