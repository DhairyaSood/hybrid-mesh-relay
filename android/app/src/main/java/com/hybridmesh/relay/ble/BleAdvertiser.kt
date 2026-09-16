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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns only BLE advertising/discovery metadata.
 *
 * Each Android advertisement session gets its own callback instance. This
 * prevents a delayed callback from an older session from overwriting the state
 * of a newer nickname/advertising generation.
 */
class BleAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile
    private var advertiser: BluetoothLeAdvertiser? = null

    @Volatile
    private var activeCallback: AdvertiseCallback? = null

    @Volatile
    private var advertisedIdentityKey: String? = null

    @Volatile
    private var advertisingRequested = false

    private val _state = MutableStateFlow(BleOperationState.IDLE)
    val state: StateFlow<BleOperationState> = _state.asStateFlow()

    private val _errorCode = MutableStateFlow<Int?>(null)
    val errorCode: StateFlow<Int?> = _errorCode.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startAdvertising(identity: LocalIdentity) {
        val currentGeneration = generation.incrementAndGet()
        startAdvertising(identity, currentGeneration)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(
        identity: LocalIdentity,
        sessionGeneration: Long
    ) {
        if (!hasAdvertisePermission()) {
            fail(ERROR_PERMISSION)
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            fail(ERROR_BLUETOOTH_UNAVAILABLE)
            return
        }

        if (!safeIsEnabled(adapter)) {
            fail(ERROR_BLUETOOTH_DISABLED)
            return
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            fail(ERROR_ADVERTISING_NOT_SUPPORTED)
            return
        }

        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            fail(ERROR_ADVERTISER_UNAVAILABLE)
            return
        }

        val key = identity.nodeId + "|" + identity.deviceName
        if (
            advertisingRequested &&
            advertisedIdentityKey == key &&
            _state.value == BleOperationState.ACTIVE
        ) {
            return
        }

        val previousAdvertiser = advertiser
        val previousCallback = activeCallback
        if (previousAdvertiser != null && previousCallback != null) {
            advertisingRequested = false
            _state.value = BleOperationState.STOPPING
            runCatching {
                previousAdvertiser.stopAdvertising(previousCallback)
            }
        }

        advertisingRequested = true
        advertisedIdentityKey = key
        _state.value = BleOperationState.STARTING
        _errorCode.value = null

        val discoveryPayload = buildDiscoveryPayload(identity)

        val serviceAdvertisement = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(
                BleConstants.MANUFACTURER_ID,
                discoveryPayload
            )
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                AdvertiseSettings.ADVERTISE_MODE_BALANCED
            )
            .setTxPowerLevel(
                AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val callback = newCallback(sessionGeneration)

        advertiser = leAdvertiser
        activeCallback = callback

        try {
            leAdvertiser.startAdvertising(
                settings,
                serviceAdvertisement,
                scanResponse,
                callback
            )
        } catch (_: SecurityException) {
            if (generation.get() == sessionGeneration) {
                advertisingRequested = false
                advertiser = null
                activeCallback = null
                advertisedIdentityKey = null
                fail(ERROR_PERMISSION)
            }
        } catch (_: IllegalArgumentException) {
            if (generation.get() == sessionGeneration) {
                advertisingRequested = false
                advertiser = null
                activeCallback = null
                advertisedIdentityKey = null
                fail(ERROR_INVALID_CONFIGURATION)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun restartAdvertising(identity: LocalIdentity) {
        val sessionGeneration = generation.incrementAndGet()
        val previousAdvertiser = advertiser
        val previousCallback = activeCallback

        advertisingRequested = false
        advertiser = null
        activeCallback = null
        advertisedIdentityKey = null

        if (previousAdvertiser != null && previousCallback != null) {
            _state.value = BleOperationState.STOPPING
            runCatching {
                previousAdvertiser.stopAdvertising(previousCallback)
            }
        } else {
            _state.value = BleOperationState.IDLE
        }

        _errorCode.value = null

        // Android does not expose a stop callback. Give the Bluetooth stack a
        // small handoff window before starting the replacement session.
        mainHandler.postDelayed({
            if (generation.get() == sessionGeneration) {
                startAdvertising(identity, sessionGeneration)
            }
        }, RESTART_HANDOFF_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        generation.incrementAndGet()
        advertisingRequested = false

        val currentAdvertiser = advertiser
        val currentCallback = activeCallback
        advertiser = null
        activeCallback = null
        advertisedIdentityKey = null

        if (currentAdvertiser == null || currentCallback == null) {
            _state.value = BleOperationState.IDLE
            _errorCode.value = null
            return
        }

        _state.value = BleOperationState.STOPPING
        runCatching {
            currentAdvertiser.stopAdvertising(currentCallback)
        }
        _state.value = BleOperationState.IDLE
        _errorCode.value = null
    }

    private fun newCallback(
        sessionGeneration: Long
    ): AdvertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {
                if (generation.get() != sessionGeneration) return
                if (advertisingRequested && activeCallback === this) {
                    _errorCode.value = null
                    _state.value = BleOperationState.ACTIVE
                }
            }

            override fun onStartFailure(errorCode: Int) {
                if (generation.get() != sessionGeneration) return
                if (advertisingRequested && activeCallback === this) {
                    _state.value = BleOperationState.ERROR
                    _errorCode.value = errorCode
                }
            }
        }

    private fun buildDiscoveryPayload(identity: LocalIdentity): ByteArray {
        val nodeUuid = runCatching {
            UUID.fromString(
                identity.nodeId.removePrefix("HMR-")
            )
        }.getOrElse {
            UUID(0L, 0L)
        }

        val nicknameBytes = identity.deviceName
            .trim()
            .toByteArray(Charsets.UTF_8)
            .truncateUtf8(BleConstants.MESH_DISCOVERY_NAME_MAX_BYTES)

        return ByteBuffer
            .allocate(BleConstants.DISCOVERY_BASE_BYTES + nicknameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(BleConstants.DISCOVERY_VERSION)
                putLong(nodeUuid.mostSignificantBits)
                putLong(nodeUuid.leastSignificantBits)
                put(
                    when (identity.deviceType) {
                        NodeType.PHONE -> BleConstants.DEVICE_TYPE_PHONE
                        NodeType.RELAY -> BleConstants.DEVICE_TYPE_RELAY
                    }
                )
                put(
                    (BleConstants.CAPABILITY_CAN_RELAY.toInt() or
                        BleConstants.CAPABILITY_CAN_STORE_FORWARD.toInt()).toByte()
                )
                put(BleConstants.MESH_PROTOCOL_VERSION)
                put(nicknameBytes.size.toByte())
                put(nicknameBytes)
            }
            .array()
    }

    private fun ByteArray.truncateUtf8(
        maxBytes: Int
    ): ByteArray {
        if (size <= maxBytes) return this
        var end = maxBytes
        while (
            end > 0 &&
            (this[end - 1].toInt() and 0xC0) == 0x80
        ) {
            end--
        }
        return copyOf(end)
    }

    private fun hasAdvertisePermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(
                appContext,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeIsEnabled(
        adapter: BluetoothAdapter
    ): Boolean = runCatching {
        adapter.isEnabled
    }.getOrDefault(false)

    private fun fail(code: Int) {
        advertisingRequested = false
        _state.value = BleOperationState.ERROR
        _errorCode.value = code
    }

    companion object {
        const val ERROR_PERMISSION = -100
        const val ERROR_BLUETOOTH_UNAVAILABLE = -101
        const val ERROR_BLUETOOTH_DISABLED = -102
        const val ERROR_ADVERTISING_NOT_SUPPORTED = -104
        const val ERROR_ADVERTISER_UNAVAILABLE = -105
        const val ERROR_INVALID_CONFIGURATION = -106

        private const val RESTART_HANDOFF_MS = 200L
    }
}
