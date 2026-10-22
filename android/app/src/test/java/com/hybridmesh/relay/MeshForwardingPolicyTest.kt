package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.MeshForwardingPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshForwardingPolicyTest {
    @Test
    fun threeDeviceRouteExcludesOriginAndUsesDestinationAtRelay() {
        val origin = relayPeer("HMR-00000000-0000-0000-0000-000000000001", "AA:00")
        val nextHop = relayPeer("HMR-00000000-0000-0000-0000-000000000002", "BB:00")
        val destination = relayPeer("HMR-00000000-0000-0000-0000-000000000003", "CC:00")

        val firstHop = MeshForwardingPolicy.select(
            peers = listOf(nextHop),
            localNodeId = origin.nodeId,
            destinationNodeId = destination.nodeId,
            excludeNodeId = null,
            excludeAddress = null,
            isTransportUsable = { true }
        )
        val secondHop = MeshForwardingPolicy.select(
            peers = listOf(origin, destination),
            localNodeId = nextHop.nodeId,
            destinationNodeId = destination.nodeId,
            excludeNodeId = origin.nodeId,
            excludeAddress = origin.bleAddress,
            isTransportUsable = { true }
        )

        assertEquals(listOf(nextHop.nodeId), firstHop.map { it.nodeId })
        assertEquals(listOf(destination.nodeId), secondHop.map { it.nodeId })
    }

    private fun relayPeer(nodeId: String, address: String) = MeshPeer(
        nodeId = nodeId,
        deviceName = "Relay",
        bleAddress = address,
        bleProtocolVersion = 2,
        bleCanRelay = true,
        bleCanStoreForward = true
    )
}
