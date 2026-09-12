package com.hybridmesh.relay.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleAdvertiser(
    context: Context
) {

    private val appContext = context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(
            BluetoothManager::class.java
        )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null

    private val _state =
        MutableStateFlow(BleOperationState.IDLE)

    val state: StateFlow<BleOperationState> =
        _state.asStateFlow()

    private val _errorCode =
        MutableStateFlow<Int?>(null)

    val errorCode: StateFlow<Int?> =
        _errorCode.asStateFlow()

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {
                _errorCode.value = null
                _state.value = BleOperationState.ACTIVE
            }

            override fun onStartFailure(
                errorCode: Int
            ) {
                _errorCode.value = errorCode
                _state.value = BleOperationState.ERROR
            }
        }

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        identity: LocalIdentity
    ) {
        if (!hasPermission()) {
            _errorCode.value = ERROR_PERMISSION
            _state.value = BleOperationState.ERROR
            return
        }

        if (_state.value == BleOperationState.ACTIVE ||
            _state.value == BleOperationState.STARTING
        ) {
            return
        }

        val adapter = bluetoothAdapter ?: run {
            _errorCode.value = ERROR_BLUETOOTH_UNAVAILABLE
            _state.value = BleOperationState.ERROR
            return
        }

        if (!isBleHardwareAvailable()) {
            _errorCode.value = ERROR_BLE_UNSUPPORTED
            _state.value = BleOperationState.ERROR
            return
        }

        if (!safeIsBluetoothEnabled(adapter)) {
            _errorCode.value = ERROR_BLUETOOTH_DISABLED
            _state.value = BleOperationState.ERROR
            return
        }

        val bleAdvertiser =
            try {
                adapter.bluetoothLeAdvertiser
            } catch (exception: SecurityException) {
                null
            }

        if (bleAdvertiser == null) {
            _errorCode.value = ERROR_ADVERTISER_UNAVAILABLE
            _state.value = BleOperationState.ERROR
            return
        }

        advertiser = bleAdvertiser
        _state.value = BleOperationState.STARTING
        _errorCode.value = null

        val advertiseData =
            AdvertiseData.Builder()
                .addServiceUuid(
                    ParcelUuid(
                        BleConstants.SERVICE_UUID
                    )
                )
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        val scanResponse =
            AdvertiseData.Builder()
                .addServiceData(
                    ParcelUuid(
                        BleConstants.SERVICE_DATA_UUID
                    ),
                    buildPayload(identity)
                )
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                )
                .setConnectable(true)
                .setTimeout(0)
                .build()

        try {
            bleAdvertiser.startAdvertising(
                settings,
                advertiseData,
                scanResponse,
                advertiseCallback
            )
        } catch (exception: SecurityException) {
            _errorCode.value = ERROR_PERMISSION
            _state.value = BleOperationState.ERROR
        } catch (exception: IllegalArgumentException) {
            _errorCode.value = ERROR_INVALID_DATA
            _state.value = BleOperationState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val currentState = _state.value

        if (currentState == BleOperationState.IDLE) {
            return
        }

        _state.value = BleOperationState.STOPPING

        try {
            advertiser?.stopAdvertising(
                advertiseCallback
            )
        } catch (_: SecurityException) {
            // Permission may have disappeared while Bluetooth was changing state.
        } finally {
            advertiser = null
            _errorCode.value = null
            _state.value = BleOperationState.IDLE
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_ADVERTISE
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

    private fun buildPayload(
        identity: LocalIdentity
    ): ByteArray {

        val suffix =
            identity.nodeId
                .removePrefix("HM-")
                .takeLast(8)
                .padStart(8, '0')

        val nodeBytes =
            suffix
                .chunked(2)
                .map { pair ->
                    pair.toInt(16).toByte()
                }
                .toByteArray()

        val deviceType =
            when (identity.deviceType) {
                NodeType.PHONE ->
                    BleConstants.DEVICE_TYPE_PHONE

                NodeType.RELAY ->
                    BleConstants.DEVICE_TYPE_RELAY
            }

        return byteArrayOf(
            BleConstants.DISCOVERY_VERSION,
            nodeBytes[0],
            nodeBytes[1],
            nodeBytes[2],
            nodeBytes[3],
            deviceType
        )
    }

    companion object {
        const val ERROR_PERMISSION = -100
        const val ERROR_BLUETOOTH_UNAVAILABLE = -101
        const val ERROR_BLUETOOTH_DISABLED = -102
        const val ERROR_BLE_UNSUPPORTED = -103
        const val ERROR_ADVERTISER_UNAVAILABLE = -105
        const val ERROR_INVALID_DATA = -106
    }
}
