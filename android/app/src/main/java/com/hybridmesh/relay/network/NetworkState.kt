package com.hybridmesh.relay.network

import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.model.NodeType

enum class BluetoothState {
    UNSUPPORTED,
    OFF,
    ON
}

data class NetworkState(
    val bluetoothState: BluetoothState,
    val bleSupported: Boolean,
    val permissionsGranted: Boolean,
    val internetAvailable: Boolean,
    val networkEnabled: Boolean,
    val discoveryRequested: Boolean,
    val advertisingState: BleOperationState,
    val scanningState: BleOperationState,
    val advertisingErrorCode: Int?,
    val scanningErrorCode: Int?,
    val peers: List<BlePeer>,
    val scanResultCount: Long,
    val lastScanResultAt: Long?
) {

    val nearbyDeviceCount: Int
        get() = peers.size

    val relayNodeCount: Int
        get() = peers.count {
            it.deviceType == NodeType.RELAY
        }

    val phoneNodeCount: Int
        get() = peers.count {
            it.deviceType == NodeType.PHONE
        }
}
