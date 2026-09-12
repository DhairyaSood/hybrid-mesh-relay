package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.NetworkState
import kotlinx.coroutines.flow.StateFlow

class DevicesViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val networkManager = NetworkManager.getInstance(application)
    private val identityStore = IdentityStore.getInstance(application)

    val identity: StateFlow<com.hybridmesh.relay.model.LocalIdentity> =
        identityStore.identity

    val state: StateFlow<NetworkState> =
        networkManager.state

    fun refresh() = networkManager.refresh()


    fun clearPeers() = networkManager.clearPeers()

    fun hasRequiredBlePermissions(): Boolean =
        networkManager.hasRequiredBlePermissions()
}
