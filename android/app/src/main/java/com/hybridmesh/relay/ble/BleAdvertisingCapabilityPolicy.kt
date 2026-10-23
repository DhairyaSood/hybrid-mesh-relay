package com.hybridmesh.relay.ble

/** Capability decision for the single connectable advertisement Neyra starts. */
internal object BleAdvertisingCapabilityPolicy {
    data class Decision(
        val advertiserAvailable: Boolean,
        val multipleAdvertisingSupported: Boolean
    ) {
        // Multiple-advertisement support is unrelated to starting one session.
        val canAttemptSingleSession: Boolean
            get() = advertiserAvailable
    }

    fun evaluate(
        advertiserAvailable: Boolean,
        multipleAdvertisingSupported: Boolean
    ): Decision = Decision(advertiserAvailable, multipleAdvertisingSupported)
}
