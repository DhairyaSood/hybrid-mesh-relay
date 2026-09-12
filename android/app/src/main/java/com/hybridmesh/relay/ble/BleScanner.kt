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
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.model.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BleScanner(
    context: Context,
    private val localNodeIdProvider: () -> String
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleOperationState.IDLE)
    val state: StateFlow<BleOperationState> = _state.asStateFlow()

    private val _errorCode = MutableStateFlow<Int?>(null)
    val errorCode: StateFlow<Int?> = _errorCode.asStateFlow()

    private val _peers = MutableStateFlow<List<BlePeer>>(emptyList())
    val peers: StateFlow<List<BlePeer>> = _peers.asStateFlow()

    private val _scanResultCount = MutableStateFlow(0L)
    val scanResultCount: StateFlow<Long> = _scanResultCount.asStateFlow()

    private val _lastResultAt = MutableStateFlow<Long?>(null)
    val lastResultAt: StateFlow<Long?> = _lastResultAt.asStateFlow()

    private val discoveredPeers = mutableMapOf<String, BlePeer>()
    @Volatile private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (!scanning) return
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = BleOperationState.ERROR
            _errorCode.value = errorCode
            clearPeers()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasScanPermission()) {
            setError(ERROR_PERMISSION)
            return
        }

        if (scanning) return

        val adapter = bluetoothAdapter
        if (adapter == null) {
            setError(ERROR_BLUETOOTH_UNAVAILABLE)
            return
        }

        if (!safeIsBluetoothEnabled(adapter)) {
            clearPeers()
            setError(ERROR_BLUETOOTH_DISABLED)
            return
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }

        if (scanner == null) {
            setError(ERROR_SCANNER_UNAVAILABLE)
            return
        }

        clearPeers()
        _scanResultCount.value = 0L
        _lastResultAt.value = null
        _errorCode.value = null
        _state.value = BleOperationState.STARTING
        scanning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )
            _state.value = BleOperationState.ACTIVE
        } catch (_: SecurityException) {
            scanning = false
            setError(ERROR_PERMISSION)
        } catch (_: IllegalArgumentException) {
            scanning = false
            setError(ERROR_INVALID_CONFIGURATION)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning(clearPeers: Boolean = true) {
        if (!scanning) {
            if (clearPeers) clearPeers()
            _state.value = BleOperationState.IDLE
            _errorCode.value = null
            return
        }

        scanning = false
        _state.value = BleOperationState.STOPPING
        runCatching {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }

        if (clearPeers) clearPeers()

        _state.value = BleOperationState.IDLE
        _errorCode.value = null
    }

    fun clearPeers() {
        synchronized(discoveredPeers) {
            discoveredPeers.clear()
        }
        _peers.value = emptyList()
    }

    fun expireStalePeers(now: Long = System.currentTimeMillis()) {
        if (!scanning) return

        val changed = synchronized(discoveredPeers) {
            val before = discoveredPeers.size
            discoveredPeers.entries.removeIf {
                now - it.value.lastSeen >
                    BleConstants.PEER_STALE_AFTER_MS
            }
            before != discoveredPeers.size
        }

        if (changed) publishPeers()
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getManufacturerSpecificData(
            BleConstants.MANUFACTURER_ID
        ) ?: return

        if (data.size < BleConstants.DISCOVERY_BASE_BYTES) return

        try {
            val buffer = ByteBuffer
                .wrap(data)
                .order(ByteOrder.BIG_ENDIAN)

            if (buffer.get() != BleConstants.DISCOVERY_VERSION) return

            val nodeUuid = UUID(
                buffer.long,
                buffer.long
            )

            val nodeId = "HMR-$nodeUuid"
            if (nodeId.equals(localNodeIdProvider(), true)) return

            val deviceType = when (buffer.get()) {
                BleConstants.DEVICE_TYPE_PHONE -> NodeType.PHONE
                BleConstants.DEVICE_TYPE_RELAY -> NodeType.RELAY
                else -> return
            }

            val nameLength = buffer.get().toInt() and 0xFF
            if (
                nameLength > BleConstants.DISCOVERY_NAME_MAX_BYTES ||
                buffer.remaining() < nameLength
            ) return

            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)

            val deviceName = nameBytes
                .toString(Charsets.UTF_8)
                .trim()
                .ifBlank { "Hybrid Mesh Device" }

            val address = runCatching {
                result.device.address
            }.getOrDefault("Unknown")

            val peer = BlePeer(
                nodeId = nodeId,
                deviceName = deviceName,
                deviceType = deviceType,
                address = address,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis()
            )

            synchronized(discoveredPeers) {
                discoveredPeers[nodeId] = peer
            }

            _scanResultCount.value += 1L
            _lastResultAt.value = peer.lastSeen
            publishPeers()
        } catch (_: Exception) {
            // Ignore malformed advertisements.
        }
    }

    private fun publishPeers() {
        _peers.value = synchronized(discoveredPeers) {
            discoveredPeers.values
                .sortedByDescending(BlePeer::rssi)
        }
    }

    private fun hasScanPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(
                appContext,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeIsBluetoothEnabled(
        adapter: BluetoothAdapter
    ): Boolean = runCatching {
        adapter.isEnabled
    }.getOrDefault(false)

    private fun setError(code: Int) {
        scanning = false
        _errorCode.value = code
        _state.value = BleOperationState.ERROR
    }

    companion object {
        const val ERROR_PERMISSION = -200
        const val ERROR_BLUETOOTH_UNAVAILABLE = -201
        const val ERROR_BLUETOOTH_DISABLED = -202
        const val ERROR_SCANNER_UNAVAILABLE = -203
        const val ERROR_INVALID_CONFIGURATION = -204
    }
}
