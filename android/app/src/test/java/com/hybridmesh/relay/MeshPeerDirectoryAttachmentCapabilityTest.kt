package com.hybridmesh.relay

import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.messaging.mesh.MeshPeerDirectory
import com.hybridmesh.relay.model.NodeType
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
}
