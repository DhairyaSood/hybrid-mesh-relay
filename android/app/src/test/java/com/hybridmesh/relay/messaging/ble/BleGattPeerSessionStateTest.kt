package com.hybridmesh.relay.messaging.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattPeerSessionStateTest {
    @Test
    fun delayedDisconnectForPreviousConnectionCannotClearReplacementState() {
        val state = BleGattPeerSessionState<String>()
        val address = "AA:BB:CC:DD:EE:FF"
        val connectionEvents = mutableListOf<Pair<Long, Boolean>>()

        state.onConnected(address, connectionId = 101L) { connectionEvents += it to true }
        state.setSubscribed(address, subscribed = true)
        state.withAssemblies { assemblies -> assemblies["$address|101|old-transfer"] = "old partial" }

        // B's CONNECTED callback is delivered before A's delayed DISCONNECTED callback.
        state.onConnected(address, connectionId = 102L) { connectionEvents += it to true }
        state.setSubscribed(address, subscribed = true)
        state.withAssemblies { assemblies -> assemblies["$address|102|new-transfer"] = "new partial" }

        val staleDisconnect = state.onDisconnected(
            address,
            isAddressConnected = { true },
            onClosed = { connectionEvents += it to false }
        )

        assertNull(staleDisconnect)
        assertEquals(listOf(101L to true, 102L to true), connectionEvents)
        assertEquals(102L, state.currentConnectionId(address))
        assertNull(state.withSubscribedSession(address, expectedConnectionId = 101L) { "stale ack" })
        assertEquals(
            "current ack",
            state.withSubscribedSession(address, expectedConnectionId = 102L) { "current ack" }
        )
        assertEquals(
            mapOf("$address|102|new-transfer" to "new partial"),
            state.withAssemblies { assemblies -> assemblies.toMap() }
        )

        assertEquals(
            102L,
            state.onDisconnected(address, isAddressConnected = { false }) {
                connectionEvents += it to false
            }
        )
        assertEquals(listOf(101L to true, 102L to true, 102L to false), connectionEvents)
        assertNull(state.currentConnectionId(address))
        assertNull(state.withSubscribedSession(address, expectedConnectionId = 102L) { "late ack" })
        assertTrue(state.withAssemblies { assemblies -> assemblies.isEmpty() })
    }
}
