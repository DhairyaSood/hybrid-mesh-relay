package com.hybridmesh.relay.network

import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.model.NodeType
import com.hybridmesh.relay.permissions.BlePermissionState

data class NetworkState(
    val bluetoothState: BluetoothState,
    val initializationState: InitializationState,
    val bleRuntimeState: BleRuntimeState,
    val bleSupported: Boolean,
    val blePermissions: BlePermissionState,
    val permissionsGranted: Boolean,
    val internetAvailable: Boolean,
    val networkEnabled: Boolean,
    val discoveryRequested: Boolean,
    val gattServerReady: Boolean,
    val advertisingState: BleOperationState,
    val scanningState: BleOperationState,
    val advertisingErrorCode: Int?,
    val scanningErrorCode: Int?,
    val peers: List<BlePeer>,
    val scanResultCount: Long,
    val lastScanResultAt: Long?,
    val runtimeGeneration: Long
) {
    val nearbyDeviceCount: Int
        get() = peers.size

    val relayNodeCount: Int
        get() = peers.count { it.canRelay }

    val phoneNodeCount: Int
        get() = peers.count { it.deviceType == NodeType.PHONE }
}
