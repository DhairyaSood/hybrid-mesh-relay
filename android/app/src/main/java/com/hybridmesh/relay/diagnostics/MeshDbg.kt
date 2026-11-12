package com.hybridmesh.relay.diagnostics

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Logcat tracing for the mesh delivery path. Every line is tagged MESHDBG and
 * prefixed with its stage so `adb logcat -s MESHDBG` shows where a chain breaks:
 *
 *   ADV   advertising started/active/failed
 *   SCAN  scan result parsed or rejected (with reason)
 *   PEER  peer added/updated/lost in the mesh peer directory
 *   ROUTE next hops chosen, or none chosen (with per-peer reasons)
 *   SEND  per-transport send attempt and result
 *   RX    packet received, validated or rejected (with reason)
 *   FWD   relay row forwarded or dropped (with reason)
 */
object MeshDbg {
    const val TAG = "MESHDBG"
    private const val MAX_THROTTLE_KEYS = 512
    private val lastLoggedAt = ConcurrentHashMap<String, Long>()

    fun log(stage: String, message: String) {
        // android.util.Log is not mocked in JVM unit tests; diagnostics must never throw.
        runCatching { Log.d(TAG, "$stage $message") }
    }

    /** Logs at most once per [intervalMs] for [key]; use for per-advertisement events. */
    fun throttled(key: String, intervalMs: Long, stage: String, message: String) {
        val now = System.currentTimeMillis()
        val previous = lastLoggedAt[key]
        if (previous != null && now - previous < intervalMs) return
        if (lastLoggedAt.size > MAX_THROTTLE_KEYS) lastLoggedAt.clear()
        lastLoggedAt[key] = now
        log(stage, message)
    }

    /** Short node label for log lines: last 8 hex digits of the node UUID. */
    fun node(nodeId: String?): String = nodeId?.takeLast(8) ?: "unknown"
}
