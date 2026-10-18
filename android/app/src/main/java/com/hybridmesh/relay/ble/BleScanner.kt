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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.model.NodeType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(
    context: Context,
    private val localNodeIdProvider: () -> String
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
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
    private val _rawScanResultCount = MutableStateFlow(0L)
    val rawScanResultCount: StateFlow<Long> = _rawScanResultCount.asStateFlow()
    private val _lastRawResultAt = MutableStateFlow<Long?>(null)
    val lastRawResultAt: StateFlow<Long?> = _lastRawResultAt.asStateFlow()

    private val discoveredPeers = linkedMapOf<String, BlePeer>()
    private val generation = AtomicLong(0L)
    @Volatile private var scanning = false
    @Volatile private var scanStartedElapsedRealtime = 0L
    @Volatile private var lastRawResultElapsedRealtime: Long? = null

    @Synchronized
    fun startScanning() {
        startScanningInternal(resetResults = true)
    }

    /** Restarts only the Android scan registration while preserving peers and session counters. */
    @Synchronized
    fun restartScanningPreservingResults(): Boolean {
        if (_state.value != BleOperationState.ACTIVE || !scanning) return false

        val oldCallback = activeCallback
        generation.incrementAndGet()
        scanning = false
        _state.value = BleOperationState.STOPPING
        if (oldCallback != null) {
            runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(oldCallback) }
        }
        activeCallback = null
        startScanningInternal(resetResults = false)
        return _state.value == BleOperationState.ACTIVE
    }

    private fun startScanningInternal(resetResults: Boolean) {
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

        val scanner = try { adapter.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (scanner == null) {
            setError(ERROR_SCANNER_UNAVAILABLE)
            return
        }

        val sessionGeneration = generation.incrementAndGet()
        if (resetResults) {
            clearPeers()
            _scanResultCount.value = 0L
            _lastResultAt.value = null
            _rawScanResultCount.value = 0L
            _lastRawResultAt.value = null
            lastRawResultElapsedRealtime = null
        }
        scanStartedElapsedRealtime = SystemClock.elapsedRealtime()
        _errorCode.value = null
        _state.value = BleOperationState.STARTING
        scanning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0L)
            .build()
        val callback = newScanCallback(sessionGeneration)

        try {
            scanner.startScan(listOf(filter), settings, callback)
            if (generation.get() == sessionGeneration && scanning) {
                _state.value = BleOperationState.ACTIVE
            } else {
                runCatching { scanner.stopScan(callback) }
            }
        } catch (_: SecurityException) {
            if (generation.get() == sessionGeneration) {
                scanning = false
                setError(ERROR_PERMISSION)
            }
        } catch (_: IllegalArgumentException) {
            if (generation.get() == sessionGeneration) {
                scanning = false
                setError(ERROR_INVALID_CONFIGURATION)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScanning(clearPeers: Boolean = true) {
        generation.incrementAndGet()
        scanning = false
        val previousState = _state.value
        if (previousState != BleOperationState.IDLE) _state.value = BleOperationState.STOPPING
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(activeCallback) }
        activeCallback = null
        if (clearPeers) clearPeers()
        _state.value = BleOperationState.IDLE
        _errorCode.value = null
    }

    fun clearPeers() {
        synchronized(discoveredPeers) { discoveredPeers.clear() }
        _peers.value = emptyList()
    }

    fun expireStalePeers(now: Long = System.currentTimeMillis()) {
        if (!scanning) return
        val changed = synchronized(discoveredPeers) {
            val before = discoveredPeers.size
            discoveredPeers.entries.removeIf { now - it.value.lastSeen > BleConstants.PEER_STALE_AFTER_MS }
            before != discoveredPeers.size
        }
        if (changed) publishPeers()
    }

    @Volatile
    private var activeCallback: ScanCallback? = null

    private fun newScanCallback(sessionGeneration: Long): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (generation.get() != sessionGeneration || !scanning) return
                recordRawScanResult()
                handleScanResult(result, sessionGeneration)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (generation.get() != sessionGeneration || !scanning) return
                results.forEach {
                    recordRawScanResult()
                    handleScanResult(it, sessionGeneration)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (generation.get() != sessionGeneration) return
                scanning = false
                _state.value = BleOperationState.ERROR
                _errorCode.value = errorCode
                clearPeers()
            }
        }.also { activeCallback = it }

    private fun recordRawScanResult() {
        _rawScanResultCount.value += 1L
        _lastRawResultAt.value = System.currentTimeMillis()
        lastRawResultElapsedRealtime = SystemClock.elapsedRealtime()
    }

    fun scanSilenceDurationMs(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long? {
        if (!scanning) return null
        val reference = lastRawResultElapsedRealtime ?: scanStartedElapsedRealtime
        return (nowElapsedRealtime - reference).coerceAtLeast(0L)
    }

    private fun handleScanResult(result: ScanResult, sessionGeneration: Long) {
        if (generation.get() != sessionGeneration || !scanning) return
        val record = result.scanRecord ?: return
        val data = record.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID) ?: return
        if (data.size < BleConstants.LEGACY_DISCOVERY_BASE_BYTES) return

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val discoveryVersion = buffer.get()
            if (discoveryVersion != BleConstants.DISCOVERY_VERSION &&
                discoveryVersion != 3.toByte() &&
                discoveryVersion != BleConstants.LEGACY_DISCOVERY_VERSION
            ) return
            val nodeUuid = UUID(buffer.long, buffer.long)
            val nodeId = "HMR-$nodeUuid"
            if (nodeId.equals(localNodeIdProvider(), true)) return

            val deviceType = when (buffer.get()) {
                BleConstants.DEVICE_TYPE_PHONE -> NodeType.PHONE
                BleConstants.DEVICE_TYPE_RELAY -> NodeType.RELAY
                else -> return
            }

            val meshProtocolVersion: Int
            val canRelay: Boolean
            val canStoreForward: Boolean
            val supportsAttachmentHopIdentity: Boolean
            val nameLength: Int

            when (discoveryVersion.toInt()) {
                BleConstants.DISCOVERY_VERSION.toInt() -> {
                    if (buffer.remaining() < 3) return
                    val capabilities = buffer.get().toInt() and 0xFF
                    meshProtocolVersion = buffer.get().toInt() and 0xFF
                    canRelay = capabilities and BleConstants.CAPABILITY_CAN_RELAY.toInt() != 0
                    canStoreForward =
                        capabilities and BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt() != 0
                    supportsAttachmentHopIdentity =
                        capabilities and BleConstants.CAPABILITY_ATTACHMENT_HOP_IDENTITY.toInt() != 0
                    nameLength = buffer.get().toInt() and 0xFF
                }
                3 -> {
                    if (buffer.remaining() < 2) return
                    val capabilities = buffer.get().toInt() and 0xFF
                    meshProtocolVersion = BleConstants.LEGACY_MESH_PROTOCOL_VERSION.toInt()
                    canRelay = capabilities and BleConstants.CAPABILITY_CAN_RELAY.toInt() != 0
                    canStoreForward =
                        capabilities and BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt() != 0
                    supportsAttachmentHopIdentity = false
                    nameLength = buffer.get().toInt() and 0xFF
                }
                else -> {
                    meshProtocolVersion = 0
                    canRelay = false
                    canStoreForward = false
                    supportsAttachmentHopIdentity = false
                    nameLength = buffer.get().toInt() and 0xFF
                }
            }

            if (nameLength > BleConstants.DISCOVERY_NAME_MAX_BYTES || buffer.remaining() < nameLength) return
            if (discoveryVersion.toInt() >= 3 && meshProtocolVersion < 1) return
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val deviceName = nameBytes.toString(Charsets.UTF_8).trim().ifBlank { "Hybrid Mesh Device" }
            val address = runCatching { result.device.address }.getOrDefault("Unknown")
            val seenAt = System.currentTimeMillis()

            synchronized(discoveredPeers) {
                val previous = discoveredPeers[nodeId]
                discoveredPeers[nodeId] = BlePeer(
                    nodeId = nodeId,
                    deviceName = deviceName,
                    deviceType = deviceType,
                    address = address,
                    rssi = result.rssi,
                    lastSeen = seenAt,
                    meshProtocolVersion = meshProtocolVersion,
                    canRelay = canRelay,
                    canStoreForward = canStoreForward,
                    supportsAttachmentHopIdentity = supportsAttachmentHopIdentity
                ).let { fresh ->
                    // Preserve first-seen ordering. RSSI is telemetry, not the list sort key.
                    if (previous == null) fresh else fresh
                }
            }
            _scanResultCount.value += 1L
            _lastResultAt.value = seenAt
            publishPeers()
        } catch (_: Exception) {
            // Ignore malformed advertisements rather than destabilizing discovery.
        }
    }

    private fun publishPeers() {
        _peers.value = synchronized(discoveredPeers) { PeerOrderingPolicy.stableLive(discoveredPeers.values.toList()) }
    }

    private fun hasScanPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    private fun safeIsBluetoothEnabled(adapter: BluetoothAdapter): Boolean = runCatching { adapter.isEnabled }.getOrDefault(false)

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
