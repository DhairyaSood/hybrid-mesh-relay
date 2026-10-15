package com.hybridmesh.relay.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class NetworkEventKind {
    REQUEST_STARTED,
    REQUEST_SUCCEEDED,
    REQUEST_FAILED,
    REQUEST_UNAVAILABLE,
    TRANSPORT_AVAILABLE,
    TRANSPORT_UNAVAILABLE,
    HEALTH_PROBE_SUCCEEDED,
    HEALTH_PROBE_FAILED,
    LIFECYCLE,
    DISCOVERY_CANDIDATE,
    CONNECTION_STAGE
}

data class NetworkDiagnosticEvent(
    val id: Long,
    val timestamp: Long,
    val kind: NetworkEventKind,
    val operation: String,
    val correlationId: String? = null,
    val attemptId: String? = null,
    val transport: String? = null,
    val peerNodeId: String? = null,
    val durationMs: Long? = null,
    val bytes: Long = 0L,
    val attempt: Int? = null,
    val result: String? = null
)

data class NetworkDiagnosticsSnapshot(
    val events: List<NetworkDiagnosticEvent> = emptyList(),
    val droppedEvents: Long = 0L
) {
    fun inWindow(now: Long, windowMs: Long): List<NetworkDiagnosticEvent> =
        events.filter { it.timestamp >= now - windowMs }

    fun requests(now: Long, windowMs: Long): List<NetworkDiagnosticEvent> =
        inWindow(now, windowMs).filter {
            it.kind == NetworkEventKind.REQUEST_SUCCEEDED ||
                it.kind == NetworkEventKind.REQUEST_FAILED ||
                it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
        }

        fun peerHealth(peerNodeId: String, transport: String, now: Long = System.currentTimeMillis()): PeerHealth? {
            val samples = inWindow(now, PEER_HEALTH_WINDOW_MS).filter {
                it.peerNodeId.equals(peerNodeId, ignoreCase = true) &&
                    it.transport.equals(transport, ignoreCase = true) &&
                    it.operation in setOf("mesh.send", "wifi.health_probe") &&
                    it.kind in TERMINAL_REQUEST_EVENTS
            }
            if (samples.isEmpty()) return null
            val successful = samples.count {
                it.kind == NetworkEventKind.REQUEST_SUCCEEDED ||
                    it.kind == NetworkEventKind.HEALTH_PROBE_SUCCEEDED
            }
            val durations = samples.mapNotNull { it.durationMs }
            val latest = samples.maxOf { it.timestamp }
            return PeerHealth(
                successRatePercent = successful * 100.0 / samples.size,
                medianLatencyMs = percentile(durations, 0.5),
                sampleCount = samples.size,
                updatedAt = latest,
                stale = now - latest > PEER_HEALTH_STALE_MS
            )
        }

        companion object {
            private val TERMINAL_REQUEST_EVENTS = setOf(
                NetworkEventKind.REQUEST_SUCCEEDED,
                NetworkEventKind.REQUEST_FAILED,
                NetworkEventKind.REQUEST_UNAVAILABLE,
                NetworkEventKind.HEALTH_PROBE_SUCCEEDED,
                NetworkEventKind.HEALTH_PROBE_FAILED
            )
            private const val PEER_HEALTH_WINDOW_MS = 5 * 60 * 1_000L
            private const val PEER_HEALTH_STALE_MS = 30 * 1_000L
        }
}

data class PeerHealth(
        val successRatePercent: Double,
        val medianLatencyMs: Long?,
        val sampleCount: Int,
        val updatedAt: Long,
        val stale: Boolean
) {
        val label: String
            get() = when {
                stale -> "STALE"
                successRatePercent >= 95.0 && (medianLatencyMs == null || medianLatencyMs <= 100L) -> "GOOD"
                successRatePercent >= 80.0 && (medianLatencyMs == null || medianLatencyMs <= 300L) -> "FAIR"
                else -> "POOR"
            }
}

data class ConnectionQuality(
    val label: String,
    val detail: String
)

fun connectionQuality(bleRssi: Int?, wifiHealth: PeerHealth?): ConnectionQuality? {
    val effectiveWifi = wifiHealth?.takeUnless { it.stale }
    return when {
            bleRssi != null && effectiveWifi != null -> {
                val bleLabel = bleRssiLabel(bleRssi)
                val label = if (qualityRank(bleLabel) >= qualityRank(effectiveWifi.label)) {
                    bleLabel
                } else {
                    effectiveWifi.label
                }
                ConnectionQuality(
                    label = label,
                    detail = "BLE $bleRssi dBm · Wi-Fi ${effectiveWifi.medianLatencyMs?.let { "$it ms" } ?: "—"}"
                )
            }
            effectiveWifi != null -> ConnectionQuality(
                label = effectiveWifi.label,
                detail = effectiveWifi.medianLatencyMs?.let { "$it ms" } ?: "latency —"
            )
            bleRssi != null -> ConnectionQuality(
                label = bleRssiLabel(bleRssi),
                detail = "$bleRssi dBm"
            )
            else -> null
    }
}

private fun bleRssiLabel(rssi: Int): String = when {
    rssi >= -60 -> "GOOD"
    rssi >= -75 -> "FAIR"
    else -> "POOR"
}

private fun qualityRank(label: String): Int = when (label) {
    "GOOD" -> 1
    "FAIR" -> 2
    "POOR", "STALE" -> 3
    else -> 3
}

/**
 * Bounded, process-local diagnostics. It deliberately stores metadata only, never payload bytes.
 */
class NetworkDiagnostics private constructor() {
    private val nextId = AtomicLong(0L)
    private val _snapshot = MutableStateFlow(NetworkDiagnosticsSnapshot())
    val snapshot: StateFlow<NetworkDiagnosticsSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun record(
        kind: NetworkEventKind,
        operation: String,
        correlationId: String? = null,
        attemptId: String? = null,
        transport: String? = null,
        peerNodeId: String? = null,
        durationMs: Long? = null,
        bytes: Long = 0L,
        attempt: Int? = null,
        result: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val current = _snapshot.value
        val retained = (current.events + NetworkDiagnosticEvent(
            id = nextId.incrementAndGet(),
            timestamp = timestamp,
            kind = kind,
            operation = operation,
            correlationId = correlationId,
            attemptId = attemptId,
            transport = transport,
            peerNodeId = peerNodeId,
            durationMs = durationMs,
            bytes = bytes.coerceAtLeast(0L),
            attempt = attempt,
            result = result
        )).takeLast(MAX_EVENTS)
        _snapshot.value = current.copy(
            events = retained,
            droppedEvents = current.droppedEvents + if (retained.size < current.events.size + 1) 1 else 0
        )
    }

    companion object {
        private const val MAX_EVENTS = 1_000
        @Volatile private var instance: NetworkDiagnostics? = null

        fun getInstance(): NetworkDiagnostics =
            instance ?: synchronized(this) {
                instance ?: NetworkDiagnostics().also { instance = it }
            }
    }
}

fun percentile(values: List<Long>, percentile: Double): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = kotlin.math.ceil(sorted.size * percentile.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(1)
    return sorted[(rank - 1).coerceAtMost(sorted.lastIndex)]
}
