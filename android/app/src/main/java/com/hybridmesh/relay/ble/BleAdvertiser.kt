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

    private val appContext =
        context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(
            BluetoothManager::class.java
        )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null

    private val _isAdvertising =
        MutableStateFlow(false)

    val isAdvertising: StateFlow<Boolean> =
        _isAdvertising.asStateFlow()

    private val _advertisingErrorCode =
        MutableStateFlow<Int?>(null)

    val advertisingErrorCode: StateFlow<Int?> =
        _advertisingErrorCode.asStateFlow()

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {
                _advertisingErrorCode.value = null
                _isAdvertising.value = true
            }

            override fun onStartFailure(
                errorCode: Int
            ) {
                _isAdvertising.value = false
                _advertisingErrorCode.value = errorCode
            }
        }

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        identity: LocalIdentity
    ) {
        if (!hasAdvertisePermission()) {
            _isAdvertising.value = false
            _advertisingErrorCode.value =
                ERROR_PERMISSION
            return
        }

        if (_isAdvertising.value) {
            return
        }

        val adapter =
            bluetoothAdapter ?: run {
                _isAdvertising.value = false
                _advertisingErrorCode.value =
                    ERROR_BLUETOOTH_UNAVAILABLE
                return
            }

        if (!adapter.isEnabled) {
            _isAdvertising.value = false
            _advertisingErrorCode.value =
                ERROR_BLUETOOTH_DISABLED
            return
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            _isAdvertising.value = false
            _advertisingErrorCode.value =
                ERROR_ADVERTISING_NOT_SUPPORTED
            return
        }

        val bleAdvertiser =
            adapter.bluetoothLeAdvertiser ?: run {
                _isAdvertising.value = false
                _advertisingErrorCode.value =
                    ERROR_ADVERTISER_UNAVAILABLE
                return
            }

        advertiser = bleAdvertiser

        /*
         * Primary advertising packet.
         *
         * This contains only our service UUID so that
         * scanners can identify Hybrid Mesh Relay nodes.
         */
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

        /*
         * Scan response contains the compact node payload.
         */
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

        _advertisingErrorCode.value = null

        bleAdvertiser.startAdvertising(
            settings,
            advertiseData,
            scanResponse,
            advertiseCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {

        if (!hasAdvertisePermission()) {
            _isAdvertising.value = false
            return
        }

        advertiser?.stopAdvertising(
            advertiseCallback
        )

        advertiser = null

        _isAdvertising.value = false
        _advertisingErrorCode.value = null
    }

    private fun buildPayload(
        identity: LocalIdentity
    ): ByteArray {

        /*
         * Node ID format:
         *
         * HM-A1B2C3D4
         *
         * Only A1B2C3D4 is transmitted.
         */
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

    private fun hasAdvertisePermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {

        /*
         * App-defined diagnostic error codes.
         */
        const val ERROR_PERMISSION = -100

        const val ERROR_BLUETOOTH_UNAVAILABLE = -101

        const val ERROR_BLUETOOTH_DISABLED = -102

        const val ERROR_ADVERTISING_NOT_SUPPORTED = -104

        const val ERROR_ADVERTISER_UNAVAILABLE = -105
    }
}