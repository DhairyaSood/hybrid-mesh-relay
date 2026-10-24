package com.hybridmesh.relay.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSessionRegistryTest {
    private data class Session(val host: String, val marker: String)

    @Test
    fun admittingAnotherClientPreservesExistingHealthySession() {
        val registry = WifiDirectSessionRegistry<Session>(maxSessions = 8) { it.host }
        val first = Session("192.168.49.2", "first")
        val second = Session("192.168.49.3", "second")

        assertTrue(registry.admit("node-a", first.host, first).accepted)
        assertTrue(registry.admit("node-b", second.host, second).accepted)

        assertSame(first, registry["node-a"])
        assertSame(second, registry["node-b"])
        assertEquals(2, registry.values.size)
    }

    @Test
    fun reconnectReplacesOnlySamePeerAndStaleCloseCannotRemoveReplacement() {
        val registry = WifiDirectSessionRegistry<Session>(maxSessions = 8) { it.host }
        val oldA = Session("192.168.49.2", "old-a")
        val peerB = Session("192.168.49.3", "b")
        val newA = Session("192.168.49.2", "new-a")
        registry.admit("node-a", oldA.host, oldA)
        registry.admit("node-b", peerB.host, peerB)

        val replacement = registry.admit("node-a", newA.host, newA)

        assertTrue(replacement.accepted)
        assertSame(oldA, replacement.replaced)
        assertSame(newA, registry["node-a"])
        assertSame(peerB, registry["node-b"])
        assertFalse(registry.remove("node-a", oldA))
        assertSame(newA, registry["node-a"])
    }

    @Test
    fun rejectsIdentityAddressConflictsAndCapacityWithoutEvictingHealthyPeers() {
        val registry = WifiDirectSessionRegistry<Session>(maxSessions = 2) { it.host }
        val a = Session("192.168.49.2", "a")
        val b = Session("192.168.49.3", "b")
        registry.admit("node-a", a.host, a)
        registry.admit("node-b", b.host, b)

        val hijack = Session("192.168.49.99", "conflict")
        val extra = Session("192.168.49.4", "extra")
        assertFalse(registry.admit("node-a", hijack.host, hijack).accepted)
        assertFalse(registry.admit("node-c", extra.host, extra).accepted)
        assertSame(a, registry["node-a"])
        assertSame(b, registry["node-b"])
        assertEquals(2, registry.size)
    }
}
