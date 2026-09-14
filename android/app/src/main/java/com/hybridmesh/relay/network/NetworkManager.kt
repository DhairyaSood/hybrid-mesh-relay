package com.hybridmesh.relay.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.hybridmesh.relay.ble.BleAdvertiser
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BleScanner
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.permissions.BlePermissionState
import com.hybridmesh.relay.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class NetworkManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bleAdvertiser = BleAdvertiser(appContext)
    private val bleScanner = BleScanner(appContext) { identityStore.getIdentity().nodeId }

    private val _bluetoothState = MutableStateFlow(detectBluetoothState())
    private val _initializationState = MutableStateFlow(InitializationState.BOOTSTRAPPING)
    private val _internetAvailable = MutableStateFlow(detectInternetAvailability())
    private val _blePermissions = MutableStateFlow(PermissionManager.ble(appContext))
    private val _networkEnabled = MutableStateFlow(false)
    private val _discoveryRequested = MutableStateFlow(false)
    private val _gattServerReady = MutableStateFlow(false)
    private val _runtimeGeneration = MutableStateFlow(0L)

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()
    val blePermissions: StateFlow<BlePermissionState> = _blePermissions.asStateFlow()
    val runtimeGeneration: StateFlow<Long> = _runtimeGeneration.asStateFlow()

    @Volatile private var runtimeStarted = false
    private val runtimeJobs = mutableListOf<Job>()
    private var expiryJob: Job? = null
    private var bluetoothReceiverRegistered = false
    private var connectivityCallbackRegistered = false
    @Volatile private var bleAdvertisingGate: () -> Boolean = { false }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBluetoothState()
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _internetAvailable.value = detectInternetAvailability(); publishState()
        }
        override fun onLost(network: Network) {
            _internetAvailable.value = detectInternetAvailability(); publishState()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _internetAvailable.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            publishState()
        }
    }

    @Synchronized
    fun startRuntime() {
        if (runtimeStarted) { refresh(); return }
        runtimeStarted = true
        _networkEnabled.value = true
        _discoveryRequested.value = true
        _initializationState.value = InitializationState.CHECKING_PERMISSIONS
        registerBluetoothReceiver()
        registerConnectivityCallback()

        runtimeJobs += scope.launch { bleAdvertiser.state.collect { publishState() } }
        runtimeJobs += scope.launch { bleAdvertiser.errorCode.collect { publishState() } }
        runtimeJobs += scope.launch { bleScanner.state.collect { publishState() } }
        runtimeJobs += scope.launch { bleScanner.errorCode.collect { publishState() } }
        runtimeJobs += scope.launch { bleScanner.peers.collect { publishState() } }
        runtimeJobs += scope.launch { bleScanner.scanResultCount.collect { publishState() } }
        runtimeJobs += scope.launch { bleScanner.lastResultAt.collect { publishState() } }
        runtimeJobs += scope.launch {
            identityStore.identity.drop(1).collect { identity ->
                if (identityStore.isNicknameConfigured() && isBleRuntimeUsable() && bleAdvertisingGate()) {
                    bleAdvertiser.restartAdvertising(identity)
                } else {
                    bleAdvertiser.stopAdvertising()
                }
                publishState()
            }
        }

        expiryJob = scope.launch {
            while (runtimeStarted) {
                bleScanner.expireStalePeers()
                delay(2_000L)
            }
        }
        refresh()
    }

    @Synchronized
    fun stopRuntime() {
        if (!runtimeStarted) return
        runtimeStarted = false
        _networkEnabled.value = false
        _discoveryRequested.value = false
        incrementGeneration()
        runtimeJobs.forEach(Job::cancel); runtimeJobs.clear()
        expiryJob?.cancel(); expiryJob = null
        runCatching { if (bluetoothReceiverRegistered) appContext.unregisterReceiver(bluetoothReceiver) }
        bluetoothReceiverRegistered = false
        runCatching { if (connectivityCallbackRegistered) connectivityManager.unregisterNetworkCallback(connectivityCallback) }
        connectivityCallbackRegistered = false
        bleScanner.stopScanning(clearPeers = true)
        bleAdvertiser.stopAdvertising()
        _gattServerReady.value = false
        _initializationState.value = InitializationState.BOOTSTRAPPING
        publishState()
    }

    fun refresh() {
        _initializationState.value = if (!runtimeStarted) InitializationState.BOOTSTRAPPING else InitializationState.CHECKING_PERMISSIONS
        _blePermissions.value = PermissionManager.ble(appContext)
        _bluetoothState.value = detectBluetoothState()
        _internetAvailable.value = detectInternetAvailability()
        reconcileRuntime()
        updateInitializationState()
        publishState()
    }

    fun setBleAdvertisingGate(gate: () -> Boolean) { bleAdvertisingGate = gate; refresh() }

    fun setGattServerReady(ready: Boolean) {
        _gattServerReady.value = ready
        updateInitializationState(); publishState()
    }

    fun clearPeers() { bleScanner.clearPeers(); publishState() }
    fun hasRequiredBlePermissions(): Boolean = _blePermissions.value.allGranted
    fun currentBlePermissions(): BlePermissionState = PermissionManager.ble(appContext)

    fun refreshBluetoothState() {
        val previous = _bluetoothState.value
        val current = detectBluetoothState()
        if (current != previous) {
            if (current == BluetoothState.TURNING_ON || current == BluetoothState.TURNING_OFF ||
                current == BluetoothState.OFF || current == BluetoothState.ERROR || previous == BluetoothState.OFF) {
                incrementGeneration()
                _gattServerReady.value = false
            }
            if (current != BluetoothState.ON) {
                bleScanner.stopScanning(clearPeers = true)
                bleAdvertiser.stopAdvertising()
            }
        }
        _bluetoothState.value = current
        if (!runtimeStarted) { updateInitializationState(); publishState(); return }
        reconcileRuntime()
        updateInitializationState()
        publishState()
    }

    private fun incrementGeneration() { _runtimeGeneration.value = _runtimeGeneration.value + 1L }

    private fun reconcileRuntime() {
        _blePermissions.value = PermissionManager.ble(appContext)
        val supported = isBleSupported()
        val permissions = _blePermissions.value
        val bluetoothReady = _bluetoothState.value == BluetoothState.ON
        when {
            !supported || !permissions.allGranted || !bluetoothReady -> {
                _gattServerReady.value = false
                bleScanner.stopScanning(clearPeers = true)
                bleAdvertiser.stopAdvertising()
            }
            runtimeStarted && _networkEnabled.value -> {
                startAdvertiserIfNeeded()
                if (_discoveryRequested.value) bleScanner.startScanning()
            }
        }
    }

    private fun updateInitializationState() {
        _initializationState.value = when {
            !isBleSupported() -> InitializationState.DEGRADED
            !_blePermissions.value.allGranted -> InitializationState.PERMISSION_REQUIRED
            _bluetoothState.value == BluetoothState.OFF || _bluetoothState.value == BluetoothState.TURNING_OFF -> InitializationState.BLUETOOTH_OFF
            _bluetoothState.value != BluetoothState.ON -> InitializationState.RECOVERING
            !runtimeStarted -> InitializationState.BOOTSTRAPPING
            bleScanner.state.value == BleOperationState.ERROR || bleAdvertiser.state.value == BleOperationState.ERROR -> InitializationState.DEGRADED
            !_gattServerReady.value -> if (_runtimeGeneration.value == 0L) InitializationState.STARTING_GATT else InitializationState.RECOVERING
            bleScanner.state.value != BleOperationState.ACTIVE || bleAdvertiser.state.value != BleOperationState.ACTIVE -> InitializationState.STARTING_DISCOVERY
            else -> InitializationState.READY
        }
    }

    private fun isBleRuntimeUsable(): Boolean = runtimeStarted &&
        _blePermissions.value.allGranted && _bluetoothState.value == BluetoothState.ON && isBleSupported()

    private fun startAdvertiserIfNeeded() {
        if (!isBleRuntimeUsable() || !identityStore.isNicknameConfigured() || !bleAdvertisingGate()) {
            bleAdvertiser.stopAdvertising(); return
        }
        if (bleAdvertiser.state.value == BleOperationState.ACTIVE || bleAdvertiser.state.value == BleOperationState.STARTING) return
        bleAdvertiser.startAdvertising(identityStore.getIdentity())
    }

    private fun buildRuntimeState(): BleRuntimeState = when {
        !isBleSupported() -> BleRuntimeState.UNSUPPORTED
        !_blePermissions.value.allGranted -> BleRuntimeState.PERMISSION_REQUIRED
        _bluetoothState.value == BluetoothState.OFF || _bluetoothState.value == BluetoothState.TURNING_OFF -> BleRuntimeState.BLUETOOTH_OFF
        _bluetoothState.value != BluetoothState.ON -> BleRuntimeState.RECOVERING
        !runtimeStarted || !_networkEnabled.value -> BleRuntimeState.STARTING
        _gattServerReady.value && bleScanner.state.value == BleOperationState.ACTIVE && bleAdvertiser.state.value == BleOperationState.ACTIVE -> BleRuntimeState.READY
        bleScanner.state.value == BleOperationState.ERROR || bleAdvertiser.state.value == BleOperationState.ERROR -> BleRuntimeState.DEGRADED
        _runtimeGeneration.value > 0L -> BleRuntimeState.RECOVERING
        else -> BleRuntimeState.STARTING
    }

    private fun buildState(): NetworkState = NetworkState(
        bluetoothState = _bluetoothState.value,
        initializationState = _initializationState.value,
        bleRuntimeState = buildRuntimeState(),
        bleSupported = isBleSupported(),
        blePermissions = _blePermissions.value,
        permissionsGranted = _blePermissions.value.allGranted,
        internetAvailable = _internetAvailable.value,
        networkEnabled = _networkEnabled.value,
        discoveryRequested = _discoveryRequested.value,
        gattServerReady = _gattServerReady.value,
        advertisingState = bleAdvertiser.state.value,
        scanningState = bleScanner.state.value,
        advertisingErrorCode = bleAdvertiser.errorCode.value,
        scanningErrorCode = bleScanner.errorCode.value,
        peers = bleScanner.peers.value,
        scanResultCount = bleScanner.scanResultCount.value,
        lastScanResultAt = bleScanner.lastResultAt.value,
        runtimeGeneration = _runtimeGeneration.value
    )

    private fun publishState() { updateInitializationState(); _state.value = buildState() }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallbackRegistered) return
        runCatching { connectivityManager.registerDefaultNetworkCallback(connectivityCallback); connectivityCallbackRegistered = true }
    }

    private fun detectBluetoothState(): BluetoothState {
        if (!isBleSupported()) return BluetoothState.UNSUPPORTED
        val adapter = bluetoothManager?.adapter ?: return BluetoothState.UNSUPPORTED
        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_ON -> BluetoothState.ON
                BluetoothAdapter.STATE_OFF -> BluetoothState.OFF
                BluetoothAdapter.STATE_TURNING_ON -> BluetoothState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> BluetoothState.TURNING_OFF
                else -> BluetoothState.ERROR
            }
        } catch (_: SecurityException) { BluetoothState.ERROR }
    }

    private fun isBleSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private fun detectInternetAvailability(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        @Volatile private var INSTANCE: NetworkManager? = null
        fun getInstance(context: Context): NetworkManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}
