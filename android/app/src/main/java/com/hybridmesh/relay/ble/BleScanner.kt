package com.hybridmesh.relay.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.model.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(
    context: Context,
    private val localNodeIdProvider: () -> String
) {

    private val appContext = context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(
            BluetoothManager::class.java
        )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state =
        MutableStateFlow(BleOperationState.IDLE)

    val state: StateFlow<BleOperationState> =
        _state.asStateFlow()

    private val _errorCode =
        MutableStateFlow<Int?>(null)

    val errorCode: StateFlow<Int?> =
        _errorCode.asStateFlow()

    private val _peers =
        MutableStateFlow<List<BlePeer>>(
            emptyList()
        )

    val peers: StateFlow<List<BlePeer>> =
        _peers.asStateFlow()

    private val _scanResultCount =
        MutableStateFlow(0L)

    val scanResultCount: StateFlow<Long> =
        _scanResultCount.asStateFlow()

    private val _lastResultAt =
        MutableStateFlow<Long?>(null)

    val lastResultAt: StateFlow<Long?> =
        _lastResultAt.asStateFlow()

    private val discoveredPeers =
        mutableMapOf<String, BlePeer>()

    private val lock = Any()

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                _scanResultCount.value =
                    _scanResultCount.value + 1

                _lastResultAt.value =
                    System.currentTimeMillis()

                handleScanResult(result)
            }

            override fun onBatchScanResults(
                results: MutableList<ScanResult>
            ) {
                if (results.isEmpty()) {
                    return
                }

                _scanResultCount.value +=
                    results.size.toLong()

                _lastResultAt.value =
                    System.currentTimeMillis()

                results.forEach { result ->
                    handleScanResult(result)
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                _errorCode.value = errorCode
                _state.value =
                    BleOperationState.ERROR
            }
        }

    @SuppressLint("MissingPermission")
    fun startScanning() {

        if (!hasPermission()) {
            _errorCode.value = ERROR_PERMISSION
            _state.value =
                BleOperationState.ERROR
            return
        }

        if (
            _state.value == BleOperationState.ACTIVE ||
            _state.value == BleOperationState.STARTING
        ) {
            return
        }

        val adapter =
            bluetoothAdapter ?: run {
                _errorCode.value =
                    ERROR_BLUETOOTH_UNAVAILABLE
                _state.value =
                    BleOperationState.ERROR
                return
            }

        if (!isBleHardwareAvailable()) {
            _errorCode.value =
                ERROR_BLE_UNSUPPORTED
            _state.value =
                BleOperationState.ERROR
            return
        }

        if (!safeIsBluetoothEnabled(adapter)) {
            _errorCode.value =
                ERROR_BLUETOOTH_DISABLED
            _state.value =
                BleOperationState.ERROR
            return
        }

        val scanner =
            try {
                adapter.bluetoothLeScanner
            } catch (exception: SecurityException) {
                null
            }

        if (scanner == null) {
            _errorCode.value =
                ERROR_SCANNER_UNAVAILABLE
            _state.value =
                BleOperationState.ERROR
            return
        }

        synchronized(lock) {
            discoveredPeers.clear()
            _peers.value = emptyList()
        }

        _errorCode.value = null
        _state.value =
            BleOperationState.STARTING

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid(
                        BleConstants.SERVICE_UUID
                    )
                )
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .setReportDelay(0L)
                .build()

        try {
            scanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )

            _state.value =
                BleOperationState.ACTIVE

        } catch (exception: SecurityException) {
            _errorCode.value =
                ERROR_PERMISSION
            _state.value =
                BleOperationState.ERROR

        } catch (exception: IllegalArgumentException) {
            _errorCode.value =
                ERROR_INVALID_SETTINGS
            _state.value =
                BleOperationState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning(
        clearPeers: Boolean = true
    ) {
        val currentState = _state.value

        if (
            currentState == BleOperationState.IDLE &&
            !clearPeers
        ) {
            return
        }

        _state.value =
            BleOperationState.STOPPING

        try {
            bluetoothAdapter
                ?.bluetoothLeScanner
                ?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Bluetooth permission may have changed during shutdown.
        } finally {
            if (clearPeers) {
                synchronized(lock) {
                    discoveredPeers.clear()
                    _peers.value = emptyList()
                }
            }

            _state.value =
                BleOperationState.IDLE
            _errorCode.value = null
        }
    }

    fun clearPeers() {
        synchronized(lock) {
            discoveredPeers.clear()
            _peers.value = emptyList()
        }
    }

    fun expireStalePeers(
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val changed =
                discoveredPeers.entries.removeIf { (_, peer) ->
                    now - peer.lastSeen >
                        BleConstants.PEER_TIMEOUT_MS
                }

            if (changed) {
                _peers.value =
                    discoveredPeers
                        .values
                        .sortedByDescending {
                            it.rssi
                        }
            }
        }
    }

    private fun handleScanResult(
        result: ScanResult
    ) {
        val record =
            result.scanRecord
                ?: return

        val data =
            record.getServiceData(
                ParcelUuid(
                    BleConstants.SERVICE_DATA_UUID
                )
            ) ?: return

        if (
            data.size <
            BleConstants.PAYLOAD_SIZE
        ) {
            return
        }

        if (
            data[0] !=
            BleConstants.DISCOVERY_VERSION
        ) {
            return
        }

        val nodeSuffix =
            data
                .sliceArray(1..4)
                .joinToString("") { byte ->
                    "%02X".format(
                        byte.toInt() and 0xFF
                    )
                }

        val nodeId =
            "HM-$nodeSuffix"

        if (
            nodeId ==
            localNodeIdProvider()
        ) {
            return
        }

        val deviceType =
            when (data[5]) {
                BleConstants.DEVICE_TYPE_PHONE ->
                    NodeType.PHONE

                BleConstants.DEVICE_TYPE_RELAY ->
                    NodeType.RELAY

                else ->
                    return
            }

        val deviceName =
            try {
                result.device.name
                    ?.takeIf { it.isNotBlank() }
                    ?: "Hybrid Mesh Node"
            } catch (_: SecurityException) {
                "Hybrid Mesh Node"
            }

        val address =
            try {
                result.device.address
            } catch (_: SecurityException) {
                "Unknown"
            }

        val peer =
            BlePeer(
                nodeId = nodeId,
                deviceName = deviceName,
                deviceType = deviceType,
                address = address,
                rssi = result.rssi,
                lastSeen =
                    System.currentTimeMillis()
            )

        synchronized(lock) {
            discoveredPeers[nodeId] = peer

            _peers.value =
                discoveredPeers
                    .values
                    .sortedByDescending {
                        it.rssi
                    }
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBleHardwareAvailable(): Boolean {
        return appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeIsBluetoothEnabled(
        adapter: BluetoothAdapter
    ): Boolean {
        return try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val ERROR_PERMISSION = -200
        const val ERROR_BLUETOOTH_UNAVAILABLE = -201
        const val ERROR_BLUETOOTH_DISABLED = -202
        const val ERROR_BLE_UNSUPPORTED = -203
        const val ERROR_SCANNER_UNAVAILABLE = -204
        const val ERROR_INVALID_SETTINGS = -205
    }
}
