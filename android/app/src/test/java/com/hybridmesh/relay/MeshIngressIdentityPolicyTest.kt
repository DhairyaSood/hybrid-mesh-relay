package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.MeshIngressIdentityPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPacket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshIngressIdentityPolicyTest {
    private val origin = "HMR-11111111-1111-1111-1111-111111111111"
    private val otherPeer = "HMR-22222222-2222-2222-2222-222222222222"

    @Test
    fun zeroHopPacketMustMatchSessionAssociatedPeer() {
        val packet = packet(hopCount = 0)

        assertTrue(MeshIngressIdentityPolicy.isConsistent(packet, origin))
        assertFalse(MeshIngressIdentityPolicy.isConsistent(packet, otherPeer))
        assertFalse(MeshIngressIdentityPolicy.isConsistent(packet, null))
    }

    @Test
    fun relayedPacketKeepsOriginalIdentityAcrossDifferentPredecessor() {
        assertTrue(MeshIngressIdentityPolicy.isConsistent(packet(hopCount = 1), otherPeer))
        assertFalse(MeshIngressIdentityPolicy.isConsistent(packet(hopCount = 1), null))
        assertFalse(MeshIngressIdentityPolicy.isConsistent(packet(hopCount = 1), " "))
    }

    private fun packet(hopCount: Int) = MeshPacket(
        packetId = "HMR-33333333-3333-3333-3333-333333333333",
        messageId = "HMRM-11111111-1111-1111-1111-111111111111::identity",
        originNodeId = origin,
        destinationNodeId = otherPeer,
        createdAt = 1L,
        expiresAt = 2L,
        ttl = 6 - hopCount,
        hopCount = hopCount,
        packetType = MeshPacket.PacketType.DATA,
        messageType = "NORMAL",
        content = "identity"
    )
}
