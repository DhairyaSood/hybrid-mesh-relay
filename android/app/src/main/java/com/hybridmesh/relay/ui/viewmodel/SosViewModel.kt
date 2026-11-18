package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.location.SosLocationProvider
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.sos.RepositorySosSender
import com.hybridmesh.relay.sos.SosController
import com.hybridmesh.relay.sos.SosUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class SosViewModel(application: Application) : AndroidViewModel(application) {
    private val identityStore = IdentityStore.getInstance(application)
    private val repository = MessagingRepository.getInstance(application)
    private val meshPeers = MessagingManager.getInstance(application).meshPeers
    private val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val statuses: StateFlow<Map<String, String>> =
        repository.allMessages
            .map { rows -> rows.associate { it.messageId to it.status } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val knownPeers =
        repository.knownPeers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val controller = SosController(
        scope = viewModelScope,
        sender = RepositorySosSender(repository) { identityStore.getIdentity().nodeId },
        targets = ::currentTargets,
        statuses = { statuses.value },
        locate = SosLocationProvider.create(application)::locate
    )

    val state: StateFlow<SosUiState> = controller.state

    fun privacyNoticeAcknowledged(): Boolean = preferences.getBoolean(KEY_PRIVACY_ACK, false)

    fun acknowledgePrivacyNotice() {
        preferences.edit().putBoolean(KEY_PRIVACY_ACK, true).apply()
    }

    fun start() = controller.start()
    fun cancel() = controller.cancel()
    fun stop() = controller.stop()
    fun dismiss() = controller.reset()

    /** Direct neighbours seen in the last two minutes, plus saved contacts (relayed by the mesh). */
    private fun currentTargets(): Set<String> {
        val localId = identityStore.getIdentity().nodeId
        val cutoff = System.currentTimeMillis() - NEIGHBOUR_WINDOW_MS
        val neighbours = meshPeers.value.filter { it.lastSeen >= cutoff }.map { it.nodeId }
        val contacts = knownPeers.value.filter { it.isSavedForChat }.map { it.nodeId }
        return (neighbours + contacts)
            .map { it.trim().uppercase(Locale.US) }
            .filter { NodeIdGenerator.isValid(it) && !it.equals(localId, ignoreCase = true) }
            .toSet()
    }

    private companion object {
        const val PREFS = "neyra_sos"
        const val KEY_PRIVACY_ACK = "privacy_notice_ack"
        const val NEIGHBOUR_WINDOW_MS = 2 * 60_000L
    }
}
