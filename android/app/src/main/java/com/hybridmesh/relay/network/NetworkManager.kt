package com.hybridmesh.relay.network

import android.Manifest
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
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.ble.BleAdvertiser
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BleScanner
import com.hybridmesh.relay.data.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val bleScanner = BleScanner(appContext) {
        identityStore.getIdentity().nodeId
    }

    private val _bluetoothState = MutableStateFlow(detectBluetoothState())
    private val _internetAvailable = MutableStateFlow(detectInternetAvailability())
    private val _permissionsGranted = MutableStateFlow(hasRequiredPermissions())
    private val _networkEnabled = MutableStateFlow(false)
    private val _discoveryRequested = MutableStateFlow(false)

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    @Volatile
    private var runtimeStarted = false
    private val runtimeJobs = mutableListOf<Job>()
    private var expiryJob: Job? = null
    private var bluetoothReceiverRegistered = false
    private var connectivityCallbackRegistered = false
    @Volatile
    private var bleAdvertisingGate: () -> Boolean = { true }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshBluetoothState()
            }
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _internetAvailable.value = detectInternetAvailability()
            publishState()
        }

        override fun onLost(network: Network) {
            _internetAvailable.value = detectInternetAvailability()
            publishState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _internetAvailable.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            publishState()
        }
    }

    @Synchronized
    fun startRuntime() {
        if (runtimeStarted) return
        runtimeStarted = true
        _networkEnabled.value = true
        _discoveryRequested.value = true

        registerBluetoothReceiver()
        registerConnectivityCallback()

        runtimeJobs += scope.launch {
            bleAdvertiser.state.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleAdvertiser.errorCode.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleScanner.state.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleScanner.errorCode.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleScanner.peers.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleScanner.scanResultCount.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            bleScanner.lastResultAt.collect { publishState() }
        }
        runtimeJobs += scope.launch {
            identityStore.identity
                .drop(1)
                .collect { identity ->
                    if (isBleRuntimeReady() && bleAdvertisingGate()) {
                        bleAdvertiser.restartAdvertising(identity)
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

        runtimeJobs.forEach(Job::cancel)
        runtimeJobs.clear()
        expiryJob?.cancel()
        expiryJob = null

        runCatching {
            if (bluetoothReceiverRegistered) {
                appContext.unregisterReceiver(bluetoothReceiver)
            }
        }
        bluetoothReceiverRegistered = false

        runCatching {
            if (connectivityCallbackRegistered) {
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
            }
        }
        connectivityCallbackRegistered = false

        bleScanner.stopScanning(clearPeers = true)
        bleAdvertiser.stopAdvertising()
        publishState()
    }

    fun refresh() {
        _permissionsGranted.value = hasRequiredPermissions()
        _bluetoothState.value = detectBluetoothState()
        _internetAvailable.value = detectInternetAvailability()

        if (!runtimeStarted || !_permissionsGranted.value || !isBleSupported()) {
            bleScanner.stopScanning(clearPeers = true)
            bleAdvertiser.stopAdvertising()
        } else if (_bluetoothState.value == BluetoothState.ON && _networkEnabled.value) {
            startAdvertiserIfNeeded()
            if (_discoveryRequested.value) {
                bleScanner.startScanning()
            }
        }

        publishState()
    }


    fun setBleAdvertisingGate(gate: () -> Boolean) {
        bleAdvertisingGate = gate
        if (runtimeStarted) refresh()
    }

    fun clearPeers() {
        bleScanner.clearPeers()
        publishState()
    }

    fun hasRequiredBlePermissions(): Boolean = hasRequiredPermissions()

    fun refreshBluetoothState() {
        val previous = _bluetoothState.value
        val current = detectBluetoothState()
        _bluetoothState.value = current

        if (!runtimeStarted) {
            publishState()
            return
        }

        if (current != BluetoothState.ON) {
            bleScanner.stopScanning(clearPeers = true)
            bleAdvertiser.stopAdvertising()
        } else if (previous != BluetoothState.ON) {
            _permissionsGranted.value = hasRequiredPermissions()
            if (_networkEnabled.value && _permissionsGranted.value) {
                startAdvertiserIfNeeded()
                if (_discoveryRequested.value) {
                    bleScanner.startScanning()
                }
            }
        }

        publishState()
    }

    private fun isBleRuntimeReady(): Boolean =
        runtimeStarted &&
            _permissionsGranted.value &&
            _bluetoothState.value == BluetoothState.ON &&
            isBleSupported()

    private fun startAdvertiserIfNeeded() {
        if (!isBleRuntimeReady()) return
        if (!bleAdvertisingGate()) {
            bleAdvertiser.stopAdvertising()
            return
        }
        if (bleAdvertiser.state.value == BleOperationState.ACTIVE ||
            bleAdvertiser.state.value == BleOperationState.STARTING
        ) return
        bleAdvertiser.startAdvertising(identityStore.getIdentity())
    }

    private fun buildState(): NetworkState = NetworkState(
        bluetoothState = _bluetoothState.value,
        bleSupported = isBleSupported(),
        permissionsGranted = _permissionsGranted.value,
        internetAvailable = _internetAvailable.value,
        networkEnabled = _networkEnabled.value,
        discoveryRequested = _discoveryRequested.value,
        advertisingState = bleAdvertiser.state.value,
        scanningState = bleScanner.state.value,
        advertisingErrorCode = bleAdvertiser.errorCode.value,
        scanningErrorCode = bleScanner.errorCode.value,
        peers = bleScanner.peers.value,
        scanResultCount = bleScanner.scanResultCount.value,
        lastScanResultAt = bleScanner.lastResultAt.value
    )

    private fun publishState() {
        _state.value = buildState()
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = true
        }
    }

    private fun detectBluetoothState(): BluetoothState {
        if (!isBleSupported()) return BluetoothState.UNSUPPORTED
        val adapter = bluetoothManager?.adapter ?: return BluetoothState.UNSUPPORTED
        return try {
            if (adapter.isEnabled) BluetoothState.ON else BluetoothState.OFF
        } catch (_: SecurityException) {
            BluetoothState.OFF
        }
    }

    private fun isBleSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun detectInternetAvailability(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        @Volatile
        private var INSTANCE: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
