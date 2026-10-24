package com.hybridmesh.relay.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectGroupSessionGenerationTest {
    @Test
    fun handshakeFromPreviousGroupCannotFinishAfterGroupReset() {
        val generation = WifiDirectGroupSessionGeneration()
        val oldHandshakeGeneration = generation.current()
        val events = mutableListOf<String>()

        generation.advance()

        assertNull(generation.runIfCurrent(oldHandshakeGeneration) { events += "old session ready" })
        assertEquals(emptyList<String>(), events)
        assertEquals(
            Unit,
            generation.runIfCurrent(generation.current()) { events += "current session ready" }
        )
        assertEquals(listOf("current session ready"), events)
    }
}
