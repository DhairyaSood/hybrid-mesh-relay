package com.hybridmesh.relay.messaging.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiGroupRosterTest {
    @Test
    fun rosterCodecRoundTripsPeerIdentityAndCapabilities() {
        val entries = listOf(
            WifiGroupRosterEntry(
                nodeId = "HMR-00000000-0000-0000-0000-000000000001",
                deviceName = "C's Pixel",
                canRelay = true,
                canStoreForward = true
            )
        )

        val decoded = WifiGroupRosterCodec.decode(WifiGroupRosterCodec.encode(7L, entries))

        assertEquals(WifiGroupRosterCodec.Roster(7L, entries), decoded)
    }

    @Test
    fun malformedRosterIsRejected() {
        assertNull(WifiGroupRosterCodec.decode("not a roster"))
        assertNull(WifiGroupRosterCodec.decode(WifiGroupRosterCodec.encode(1L, emptyList()) + "!"))
    }

    @Test
    fun clientSeesGroupPeerAsIndirectAndUsesOwnerAsNextHop() {
        val directory = MeshPeerDirectory()
        val ownerId = "HMR-00000000-0000-0000-0000-000000000001"
        val clientId = "HMR-00000000-0000-0000-0000-000000000002"
        val recipientId = "HMR-00000000-0000-0000-0000-000000000003"
        directory.observeWifi(ownerId, "B", "192.168.49.1", 38_991, 2, true, true)
        directory.observeWifi("HMR-00000000-0000-0000-0000-000000000004", "D", "192.168.49.4", 38_991, 2, true, true)

        assertTrue(
            directory.replaceIndirectPeers(
                relayNodeId = ownerId,
                relayDeviceName = "B",
                revision = 1L,
                entries = listOf(WifiGroupRosterEntry(recipientId, "C", true, true))
            )
        )
        assertFalse(
            directory.replaceIndirectPeers(
                relayNodeId = ownerId,
                relayDeviceName = "B",
                revision = 0L,
                entries = emptyList()
            )
        )

        val indirect = directory.snapshot.value.single { it.nodeId == recipientId }
        assertTrue(indirect.isIndirect)
        assertEquals(ownerId, indirect.reachableViaNodeId)
        val nextHops = MeshForwardingPolicy.select(
            peers = directory.snapshot.value,
            localNodeId = clientId,
            destinationNodeId = recipientId,
            excludeNodeId = null,
            excludeAddress = null,
            maxFanout = 1
        )

        assertEquals(listOf(ownerId), nextHops.map { it.nodeId })

        directory.observeWifi(recipientId, "C", "192.168.49.3", 38_991, 2, true, true)
        assertFalse(directory.snapshot.value.single { it.nodeId == recipientId }.isIndirect)
        directory.removeWifi(recipientId)
        assertTrue(directory.snapshot.value.single { it.nodeId == recipientId }.isIndirect)
        directory.removeWifi(ownerId)
        assertNull(directory.snapshot.value.firstOrNull { it.nodeId == recipientId })
    }
}
