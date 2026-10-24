package com.hybridmesh.relay.wifi

/** Serializes group resets with completion of socket handshakes from that group. */
internal class WifiDirectGroupSessionGeneration {
    private val lock = Any()
    private var generation = 0L

    fun current(): Long = synchronized(lock) { generation }

    fun advance(): Long = synchronized(lock) { ++generation }

    fun <R> runIfCurrent(expectedGeneration: Long, action: () -> R): R? = synchronized(lock) {
        if (generation != expectedGeneration) return@synchronized null
        action()
    }
}
