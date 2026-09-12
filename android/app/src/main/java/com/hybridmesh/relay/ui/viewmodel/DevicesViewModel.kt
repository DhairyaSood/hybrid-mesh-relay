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

    private val networkManager =
        NetworkManager.getInstance(application)

    private val identityStore =
        IdentityStore(application)

    val identity =
        identityStore.getIdentity()

    val state: StateFlow<NetworkState> =
        networkManager.state

    fun refresh() {
        networkManager.refresh()
    }

    fun enableNetwork() {
        networkManager.enableNetwork()
    }

    fun startDiscovery() {
        networkManager.startDiscovery()
    }

    fun stopDiscovery() {
        networkManager.stopDiscovery()
    }

    fun disableNetwork() {
        networkManager.disableNetwork()
    }

    fun clearPeers() {
        networkManager.clearPeers()
    }

    fun hasRequiredBlePermissions():
        Boolean {
        return networkManager
            .hasRequiredBlePermissions()
    }

    override fun onCleared() {
        /*
         * The NetworkManager is application-scoped.
         * Leaving Devices must not shut down BLE networking.
         */
        super.onCleared()
    }
}
