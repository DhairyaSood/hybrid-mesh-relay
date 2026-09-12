package com.hybridmesh.relay.ble

import android.content.Context
import com.hybridmesh.relay.data.IdentityStore
import kotlinx.coroutines.flow.StateFlow

class BleManager(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val identityStore =
        IdentityStore(appContext)

    private val scanner =
        BleScanner(appContext)

    private val advertiser =
        BleAdvertiser(appContext)

    val peers: StateFlow<List<BlePeer>>
        get() = scanner.peers

    val isAdvertising: StateFlow<Boolean>
        get() = advertiser.isAdvertising

    val advertisingErrorCode: StateFlow<Int?>
        get() = advertiser.advertisingErrorCode

    fun start() {

        val identity =
            identityStore.getIdentity()

        advertiser.startAdvertising(
            identity
        )

        scanner.startScanning()
    }

    fun startScanning() {
        scanner.startScanning()
    }

    fun stopScanning() {
        scanner.stopScanning()
    }

    fun stop() {
        scanner.stopScanning()
        advertiser.stopAdvertising()
    }

    fun clearPeers() {
        scanner.clearPeers()
    }

    fun isScanning(): Boolean {
        return scanner.isScanning()
    }
}