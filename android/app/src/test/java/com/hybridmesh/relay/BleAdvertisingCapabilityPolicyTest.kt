package com.hybridmesh.relay

import com.hybridmesh.relay.ble.BleAdvertisingCapabilityPolicy
import com.hybridmesh.relay.ble.BleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvertisingCapabilityPolicyTest {
    @Test
    fun oneAdvertiserCanStartWithoutMultipleAdvertisingSupport() {
        val decision = BleAdvertisingCapabilityPolicy.evaluate(
            advertiserAvailable = true,
            multipleAdvertisingSupported = false
        )

        assertTrue(decision.canAttemptSingleSession)
        assertFalse(decision.multipleAdvertisingSupported)
    }

    @Test
    fun missingLeAdvertiserStillPreventsStart() {
        val decision = BleAdvertisingCapabilityPolicy.evaluate(
            advertiserAvailable = false,
            multipleAdvertisingSupported = true
        )

        assertFalse(decision.canAttemptSingleSession)
    }

    @Test
    fun bleIdentityNamesAreTruncatedAtUtf8CharacterBoundaries() {
        val discoveryName = BleConstants.encodeUtf8Truncated("abcde🙂", BleConstants.MESH_DISCOVERY_NAME_MAX_BYTES)
        val gattName = BleConstants.encodeUtf8Truncated("a".repeat(49) + "🙂", 50)

        assertEquals("abcde", BleConstants.decodeUtf8(discoveryName))
        assertEquals("a".repeat(49), BleConstants.decodeUtf8(gattName))
    }

    @Test
    fun bleIdentityUtf8RejectsMalformedSurrogatesAndWireSequences() {
        assertNull(BleConstants.decodeUtf8(byteArrayOf(0xC3.toByte())))
        assertFalse(runCatching {
            BleConstants.encodeUtf8Truncated("invalid\uD800name", 50)
        }.isSuccess)
    }
}
