package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.data.PeerEntity
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val networkManager = NetworkManager.getInstance(application)
    private val identityStore = IdentityStore.getInstance(application)
    private val repository = MessagingRepository.getInstance(application)

    val identity = identityStore.identity
    val state: StateFlow<NetworkState> = networkManager.state
    val knownPeers: StateFlow<List<PeerEntity>> = repository.knownPeers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        emptyList()
    )

    fun refresh() = networkManager.refresh()
    fun clearPeers() = networkManager.clearPeers()
    fun hasRequiredBlePermissions() = networkManager.hasRequiredBlePermissions()
}
