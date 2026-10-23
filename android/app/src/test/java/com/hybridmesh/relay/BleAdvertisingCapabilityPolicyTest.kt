package com.hybridmesh.relay

import com.hybridmesh.relay.ble.BleAdvertisingCapabilityPolicy
import org.junit.Assert.assertFalse
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
}
