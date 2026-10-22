package com.hybridmesh.relay.wifi

/** Invalidates a transport only after consecutive missed probes; success resets the counter. */
internal class ProbeFailurePolicy(private val failureThreshold: Int) {
    private var consecutiveFailures = 0
    @Volatile private var healthy = true

    val isHealthy: Boolean
        get() = healthy

    init {
        require(failureThreshold > 0)
    }

    @Synchronized
    fun record(success: Boolean): Boolean {
        if (success) {
            consecutiveFailures = 0
            healthy = true
            return false
        }
        consecutiveFailures += 1
        healthy = false
        return consecutiveFailures >= failureThreshold
    }
}
