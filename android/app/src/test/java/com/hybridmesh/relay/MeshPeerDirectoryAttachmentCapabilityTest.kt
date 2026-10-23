package com.hybridmesh.relay

import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.messaging.mesh.MeshForwardingPolicy
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import com.hybridmesh.relay.messaging.mesh.MeshPeerDirectory
import com.hybridmesh.relay.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPeerDirectoryAttachmentCapabilityTest {
    @Test
    fun attachmentCapabilityFollowsLiveBleEndpointAndClearsWhenItExpires() {
        val peerId = "HMR-11111111-1111-1111-1111-111111111111"
        val directory = MeshPeerDirectory()
        directory.observeWifi(
            nodeId = peerId,
            deviceName = "Relay",
            host = "192.0.2.10",
            port = 41000,
            protocolVersion = 2,
            canRelay = true,
            canStoreForward = true
        )
        directory.replaceBlePeers(
            listOf(
                BlePeer(
                    nodeId = peerId,
                    deviceName = "Relay",
                    deviceType = NodeType.RELAY,
                    address = "AA:BB:CC:DD:EE:FF",
                    rssi = -50,
                    lastSeen = 1L,
                    meshProtocolVersion = 2,
                    canRelay = true,
                    canStoreForward = true,
                    supportsAttachmentHopIdentity = true
                )
            )
        )

        assertTrue(directory.findByNodeId(peerId)!!.toTransportPeer().bleSupportsAttachmentHopIdentity)

        directory.replaceBlePeers(emptyList())

        assertFalse(directory.findByNodeId(peerId)!!.toTransportPeer().bleSupportsAttachmentHopIdentity)
    }

    @Test
    fun activeGattIdentityOutlivesDiscoveryCacheAndIsClearedOnDisconnect() {
        val peerId = "HMR-11111111-1111-1111-1111-111111111111"
        val address = "AA:BB:CC:DD:EE:FF"
        val directory = MeshPeerDirectory()
        val discoveredAt = 1_000L
        directory.observeWifi(
            nodeId = peerId,
            deviceName = "Relay",
            host = "192.0.2.20",
            port = 42000,
            protocolVersion = 2,
            canRelay = true,
            canStoreForward = true,
            observedAt = discoveredAt
        )
        directory.replaceBlePeers(
            listOf(
                BlePeer(
                    nodeId = peerId,
                    deviceName = "Relay",
                    deviceType = NodeType.RELAY,
                    address = address,
                    rssi = -50,
                    lastSeen = discoveredAt,
                    meshProtocolVersion = 2,
                    canRelay = true,
                    canStoreForward = true
                )
            ),
            now = discoveredAt
        )
        assertEquals(peerId, directory.onBleConnectionEstablished(address, connectionId = 1L))

        // More than the old two minute discovery cache lifetime has elapsed,
        // while the logically active GATT connection has not closed.
        directory.replaceBlePeers(emptyList(), now = discoveredAt + 10 * 60 * 1_000L)

        assertEquals(peerId, directory.findNodeIdByBleAddress(address))
        assertEquals(null, directory.findLiveNodeIdByBleAddress(address))
        val alternative = MeshPeer(
            nodeId = "HMR-33333333-3333-3333-3333-333333333333",
            deviceName = "Alternative relay",
            bleAddress = "BB:CC:DD:EE:FF:00",
            bleProtocolVersion = 2,
            bleCanRelay = true,
            bleCanStoreForward = true
        )
        val nextHops = MeshForwardingPolicy.select(
            peers = directory.snapshot.value + alternative,
            localNodeId = "HMR-44444444-4444-4444-4444-444444444444",
            destinationNodeId = "HMR-55555555-5555-5555-5555-555555555555",
            excludeNodeId = directory.findNodeIdByBleAddress(address),
            excludeAddress = address,
            isTransportUsable = { true }
        )
        assertEquals(listOf(alternative.nodeId), nextHops.map { it.nodeId })
        assertTrue(directory.onBleConnectionClosed(address, connectionId = 1L))
        assertEquals(null, directory.findNodeIdByBleAddress(address))
    }

    @Test
    fun duplicateAndStaleGattEventsCannotReplaceOrClearReconnectIdentity() {
        val firstNodeId = "HMR-11111111-1111-1111-1111-111111111111"
        val secondNodeId = "HMR-22222222-2222-2222-2222-222222222222"
        val address = "AA:BB:CC:DD:EE:FF"
        val directory = MeshPeerDirectory()
        fun peer(nodeId: String, lastSeen: Long) = BlePeer(
            nodeId = nodeId,
            deviceName = "Peer",
            deviceType = NodeType.PHONE,
            address = address,
            rssi = -50,
            lastSeen = lastSeen,
            meshProtocolVersion = 2
        )

        directory.replaceBlePeers(listOf(peer(firstNodeId, 1_000L)), now = 1_000L)
        assertEquals(firstNodeId, directory.onBleConnectionEstablished(address, connectionId = 10L))

        // Repeated CONNECTED callbacks for the same platform session do not
        // rebind an active address when a later discovery observation changes.
        directory.replaceBlePeers(listOf(peer(secondNodeId, 2_000L)), now = 2_000L)
        assertEquals(firstNodeId, directory.onBleConnectionEstablished(address, connectionId = 10L))
        assertEquals(firstNodeId, directory.findNodeIdByBleAddress(address))

        // The reconnect is a new generation, and a delayed old disconnect must
        // not remove that newer identity binding.
        assertEquals(secondNodeId, directory.onBleConnectionEstablished(address, connectionId = 11L))
        assertFalse(directory.onBleConnectionClosed(address, connectionId = 10L))
        assertEquals(secondNodeId, directory.findNodeIdByBleAddress(address))
        assertTrue(directory.onBleConnectionClosed(address, connectionId = 11L))
        assertEquals(0, directory.activeBleConnectionCount())
        assertEquals(secondNodeId, directory.findNodeIdByBleAddress(address))
    }

    @Test
    fun newConnectionDoesNotInheritExpiredDiscoveryIdentityForReusedAddress() {
        val oldNodeId = "HMR-11111111-1111-1111-1111-111111111111"
        val address = "AA:BB:CC:DD:EE:FF"
        val directory = MeshPeerDirectory()
        directory.replaceBlePeers(
            listOf(
                BlePeer(
                    nodeId = oldNodeId,
                    deviceName = "Old peer",
                    deviceType = NodeType.PHONE,
                    address = address,
                    rssi = -50,
                    lastSeen = 1_000L,
                    meshProtocolVersion = 2
                )
            ),
            now = 1_000L
        )
        assertEquals(oldNodeId, directory.onBleConnectionEstablished(address, 1L))
        assertTrue(directory.onBleConnectionClosed(address, 1L))
        directory.replaceBlePeers(emptyList(), now = 4 * 60 * 1_000L)

        // Without a fresh live observation, a new session remains unidentified
        // instead of inheriting the recycled address's old Node ID.
        assertEquals(null, directory.onBleConnectionEstablished(address, 2L))
        assertEquals(null, directory.findNodeIdByBleAddress(address))
    }
}
