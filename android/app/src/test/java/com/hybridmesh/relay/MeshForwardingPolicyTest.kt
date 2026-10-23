package com.hybridmesh.relay

import com.hybridmesh.relay.messaging.mesh.MeshForwardingPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun legacyDestinationIsSelectableWithoutRelayCapabilities() {
        val destinationId = "HMR-00000000-0000-0000-0000-000000000003"
        val destination = MeshPeer(
            nodeId = destinationId,
            deviceName = "Legacy phone",
            bleAddress = "CC:00",
            bleProtocolVersion = 0,
            bleCanRelay = false,
            bleCanStoreForward = false
        )

        val selected = MeshForwardingPolicy.select(
            peers = listOf(destination),
            localNodeId = "HMR-00000000-0000-0000-0000-000000000001",
            destinationNodeId = destinationId,
            excludeNodeId = null,
            excludeAddress = null,
            isTransportUsable = { it.hasAnyEndpoint() }
        )

        assertEquals(listOf(destinationId), selected.map { it.nodeId })
    }

    @Test
    fun legacyDestinationCanBeReachedThroughEligibleRelay() {
        val originId = "HMR-00000000-0000-0000-0000-000000000001"
        val relay = relayPeer("HMR-00000000-0000-0000-0000-000000000002", "BB:00")
        val destinationId = "HMR-00000000-0000-0000-0000-000000000003"
        val destination = MeshPeer(
            nodeId = destinationId,
            deviceName = "Legacy phone",
            bleAddress = "CC:00",
            bleProtocolVersion = 1
        )
        val firstHop = MeshForwardingPolicy.select(
            peers = listOf(relay),
            localNodeId = originId,
            destinationNodeId = destinationId,
            excludeNodeId = null,
            excludeAddress = null,
            isRelayTransportUsable = { it.bleCanRelay && it.bleCanStoreForward }
        )
        val secondHop = MeshForwardingPolicy.select(
            peers = listOf(relay, destination),
            localNodeId = relay.nodeId,
            destinationNodeId = destinationId,
            excludeNodeId = originId,
            excludeAddress = "AA:00",
            isRelayTransportUsable = { it.bleCanRelay && it.bleCanStoreForward }
        )

        assertEquals(listOf(relay.nodeId), firstHop.map { it.nodeId })
        assertEquals(listOf(destinationId), secondHop.map { it.nodeId })
    }

    @Test
    fun peerWithoutRelayCapabilitiesIsNotSelectedAsIntermediate() {
        val limited = MeshPeer(
            nodeId = "HMR-00000000-0000-0000-0000-000000000002",
            deviceName = "Limited peer",
            bleAddress = "BB:00",
            bleProtocolVersion = 2
        )
        val selected = MeshForwardingPolicy.select(
            peers = listOf(limited),
            localNodeId = "HMR-00000000-0000-0000-0000-000000000001",
            destinationNodeId = "HMR-00000000-0000-0000-0000-000000000003",
            excludeNodeId = null,
            excludeAddress = null,
            isRelayTransportUsable = { it.bleCanRelay && it.bleCanStoreForward }
        )

        assertTrue(selected.isEmpty())
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
