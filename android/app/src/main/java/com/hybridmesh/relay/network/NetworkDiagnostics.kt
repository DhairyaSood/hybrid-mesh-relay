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
    TRANSPORT_UNAVAILABLE
}

data class NetworkDiagnosticEvent(
    val id: Long,
    val timestamp: Long,
    val kind: NetworkEventKind,
    val operation: String,
    val correlationId: String? = null,
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
