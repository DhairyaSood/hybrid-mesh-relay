package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybridmesh.relay.ble.BleManager
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.data.IdentityStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DevicesViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val bleManager =
        BleManager(application)

    private val identityStore =
        IdentityStore(application)

    val identity =
        identityStore.getIdentity()

    val peers: StateFlow<List<BlePeer>> =
        bleManager.peers.stateIn(
            scope = viewModelScope,
            started =
                SharingStarted.WhileSubscribed(
                    5_000
                ),
            initialValue = emptyList()
        )

    val isAdvertising:
            StateFlow<Boolean> =
        bleManager.isAdvertising.stateIn(
            scope = viewModelScope,
            started =
                SharingStarted.Eagerly,
            initialValue = false
        )

    val advertisingErrorCode:
            StateFlow<Int?> =
        bleManager.advertisingErrorCode.stateIn(
            scope = viewModelScope,
            started =
                SharingStarted.Eagerly,
            initialValue = null
        )

    fun start() {
        bleManager.start()
    }

    fun startScanning() {
        bleManager.startScanning()
    }

    fun stopScanning() {
        bleManager.stopScanning()
    }

    fun clearPeers() {
        bleManager.clearPeers()
    }

    fun isScanning(): Boolean {
        return bleManager.isScanning()
    }

    override fun onCleared() {
        bleManager.stop()
        super.onCleared()
    }
}