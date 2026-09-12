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

    private val _peers =
        MutableStateFlow<List<BlePeer>>(
            emptyList()
        )

    val peers: StateFlow<List<BlePeer>> =
        _peers.asStateFlow()

    private val discoveredPeers =
        mutableMapOf<String, BlePeer>()

    private var scanning = false

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(
                results: MutableList<ScanResult>
            ) {
                results.forEach { result ->
                    handleScanResult(result)
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                scanning = false
            }
        }

    @SuppressLint("MissingPermission")
    fun startScanning() {

        if (!hasScanPermission()) {
            scanning = false
            return
        }

        if (scanning) {
            return
        }

        val adapter =
            bluetoothAdapter ?: run {
                scanning = false
                return
            }

        if (!adapter.isEnabled) {
            scanning = false
            return
        }

        val scanner: BluetoothLeScanner =
            adapter.bluetoothLeScanner ?: run {
                scanning = false
                return
            }

        /*
         * Only Hybrid Mesh Relay devices are interesting
         * to this scanner.
         *
         * ScanFilter supports filtering by service UUID.
         */
        val serviceFilter =
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
                .build()

        discoveredPeers.clear()
        _peers.value = emptyList()

        scanner.startScan(
            listOf(serviceFilter),
            settings,
            scanCallback
        )

        scanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {

        if (!scanning) {
            return
        }

        bluetoothAdapter
            ?.bluetoothLeScanner
            ?.stopScan(scanCallback)

        scanning = false
    }

    fun isScanning(): Boolean {
        return scanning
    }

    fun clearPeers() {
        discoveredPeers.clear()
        _peers.value = emptyList()
    }

    private fun handleScanResult(
        result: ScanResult
    ) {
        val record =
            result.scanRecord ?: return

        /*
         * The actual Hybrid Mesh node data is in the
         * scan response.
         */
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

        val version =
            data[0]

        if (
            version !=
            BleConstants.DISCOVERY_VERSION
        ) {
            return
        }

        /*
         * Bytes 1-4 represent the hexadecimal suffix
         * of our HM-XXXXXXXX Node ID.
         */
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

        val deviceType =
            when (data[5]) {

                BleConstants.DEVICE_TYPE_PHONE ->
                    NodeType.PHONE

                BleConstants.DEVICE_TYPE_RELAY ->
                    NodeType.RELAY

                else ->
                    return
            }

        /*
         * Android may provide a remote Bluetooth name,
         * but it is not part of our protocol identity.
         *
         * If unavailable, use a stable generic label.
         */
        val deviceName =
            try {
                result.device.name
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Hybrid Mesh Device"
            } catch (
                exception: SecurityException
            ) {
                "Hybrid Mesh Device"
            }

        val address =
            try {
                result.device.address
            } catch (
                exception: SecurityException
            ) {
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

        /*
         * Node ID is the logical identity.
         * BLE address is only the current transport endpoint.
         */
        discoveredPeers[nodeId] = peer

        _peers.value =
            discoveredPeers
                .values
                .sortedByDescending {
                    it.rssi
                }
    }

    private fun hasScanPermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }
}