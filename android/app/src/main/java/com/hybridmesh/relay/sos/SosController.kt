package com.hybridmesh.relay.sos

import com.hybridmesh.relay.location.SosLocationResult
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Hands one SOS text to the normal message pipeline. Returns the message id, or null if it could not be queued. */
fun interface SosSender {
    suspend fun send(peerNodeId: String, text: String): String?
}

enum class SosPhase { IDLE, COUNTDOWN, ACTIVE, STOPPED }

data class SosUiState(
    val phase: SosPhase = SosPhase.IDLE,
    val countdownSeconds: Int = 0,
    val sent: Int = 0,
    val delivered: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val location: SosLocationResult? = null,
    /** True once the 60 s broadcast window has closed. */
    val windowClosed: Boolean = false
) {
    /** Peers that have not confirmed delivery after the window closed. */
    val unconfirmed: Int get() = if (windowClosed) sent - delivered else 0
}

class SosController(
    private val scope: CoroutineScope,
    private val sender: SosSender,
    /** Current target node ids: recent direct neighbours plus saved contacts. */
    private val targets: () -> Set<String>,
    /** messageId -> DeliveryStatus name for local outgoing messages. */
    private val statuses: () -> Map<String, String>,
    private val locate: suspend () -> SosLocationResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val _state = MutableStateFlow(SosUiState())
    val state: StateFlow<SosUiState> = _state.asStateFlow()

    /** Latest alert message id per peer; the dedupe map for this session. */
    private val alerted = linkedMapOf<String, String>()
    private val resent = mutableSetOf<String>()
    private var job: Job? = null
    private var alertText = ""

    fun start() {
        if (_state.value.phase == SosPhase.COUNTDOWN || _state.value.phase == SosPhase.ACTIVE) return
        alerted.clear()
        resent.clear()
        _state.value = SosUiState(phase = SosPhase.COUNTDOWN, countdownSeconds = COUNTDOWN_SECONDS)
        job = scope.launch {
            val location = async { locate() }
            for (left in COUNTDOWN_SECONDS downTo 1) {
                _state.value = _state.value.copy(countdownSeconds = left)
                delay(TICK_MS)
            }
            activate(location)
        }
    }

    /** Aborts the countdown; nothing has been sent at that point. */
    fun cancel() {
        if (_state.value.phase != SosPhase.COUNTDOWN) return
        job?.cancel()
        job = null
        _state.value = SosUiState()
    }

    /** Ends an active SOS and tells every peer that received the alert that it is cancelled. */
    fun stop() {
        if (_state.value.phase != SosPhase.ACTIVE) return
        job?.cancel()
        job = null
        val recipients = alerted.keys.toList()
        _state.value = refreshed(_state.value.copy(phase = SosPhase.STOPPED))
        val text = SosPayload.formatCancelled(clock(), zone)
        scope.launch { recipients.forEach { sender.send(it, text) } }
    }

    fun reset() {
        if (_state.value.phase == SosPhase.STOPPED) _state.value = SosUiState()
    }

    private suspend fun activate(locationJob: Deferred<SosLocationResult>) {
        val location = locationJob.await()
        val fix = location.fix
        alertText = SosPayload.format(
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyMeters = fix?.accuracyMeters,
            fixAgeMs = location.ageMs,
            noLocationReason = location.reason,
            nowMs = clock(),
            zone = zone
        )
        _state.value = SosUiState(phase = SosPhase.ACTIVE, location = location)
        dispatch()
        _state.value = refreshed(_state.value)
        var elapsedSeconds = 0L
        while (true) {
            delay(TICK_MS)
            elapsedSeconds++
            if (elapsedSeconds <= WINDOW_SECONDS && elapsedSeconds % BROADCAST_INTERVAL_SECONDS == 0L) dispatch()
            _state.value = refreshed(_state.value.copy(windowClosed = elapsedSeconds >= WINDOW_SECONDS))
        }
    }

    /** Sends to targets not yet alerted, and one fresh copy to each peer whose copy FAILED. */
    private suspend fun dispatch() {
        val current = statuses()
        val failed = alerted.filter { (peer, id) ->
            current[id] == DeliveryStatus.FAILED.name && resent.add(peer)
        }.keys
        val fresh = targets() - alerted.keys
        for (peer in failed + fresh) {
            sender.send(peer, alertText)?.let { alerted[peer] = it }
        }
    }

    private fun refreshed(base: SosUiState): SosUiState {
        val current = statuses()
        val values = alerted.values.map { current[it] }
        val delivered = values.count { it == DeliveryStatus.DELIVERED.name }
        val failed = values.count { it == DeliveryStatus.FAILED.name }
        return base.copy(
            sent = values.size,
            delivered = delivered,
            failed = failed,
            pending = values.size - delivered - failed
        )
    }

    companion object {
        const val COUNTDOWN_SECONDS = 5
        const val WINDOW_SECONDS = 60L
        const val BROADCAST_INTERVAL_SECONDS = 10L
        private const val TICK_MS = 1_000L
    }
}
