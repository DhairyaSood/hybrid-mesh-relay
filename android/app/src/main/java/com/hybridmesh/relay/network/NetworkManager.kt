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
import com.hybridmesh.relay.ble.BleConstants
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
import kotlinx.coroutines.launch

class NetworkManager private constructor(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val packageManager =
        appContext.packageManager

    private val bluetoothManager =
        appContext.getSystemService(
            BluetoothManager::class.java
        )

    private val connectivityManager =
        appContext.getSystemService(
            ConnectivityManager::class.java
        )

    private val identityStore =
        IdentityStore(appContext)

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private val bleAdvertiser =
        BleAdvertiser(appContext)

    private val bleScanner =
        BleScanner(appContext) {
            identityStore.getIdentity().nodeId
        }

    private val _bluetoothState =
        MutableStateFlow(
            detectBluetoothState()
        )

    private val _internetAvailable =
        MutableStateFlow(
            detectInternetAvailability()
        )

    private val _permissionsGranted =
        MutableStateFlow(
            hasRequiredPermissions()
        )

    private val _networkEnabled =
        MutableStateFlow(false)

    private val _discoveryRequested =
        MutableStateFlow(false)

    private val _state =
        MutableStateFlow(
            NetworkState(
                bluetoothState =
                    _bluetoothState.value,
                bleSupported =
                    isBleSupported(),
                permissionsGranted =
                    _permissionsGranted.value,
                internetAvailable =
                    _internetAvailable.value,
                networkEnabled = false,
                discoveryRequested = false,
                advertisingState =
                    BleOperationState.IDLE,
                scanningState =
                    BleOperationState.IDLE,
                advertisingErrorCode = null,
                scanningErrorCode = null,
                peers = emptyList(),
                scanResultCount = 0L,
                lastScanResultAt = null
            )
        )

    val state: StateFlow<NetworkState> =
        _state.asStateFlow()

    private var monitoringStarted = false
    private var expiryJob: Job? = null

    private val bluetoothReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action ==
                    BluetoothAdapter.ACTION_STATE_CHANGED
                ) {
                    refreshBluetoothState()
                }
            }
        }

    private val connectivityCallback =
        object :
            ConnectivityManager.NetworkCallback() {

            override fun onAvailable(
                network: Network
            ) {
                _internetAvailable.value =
                    detectInternetAvailability()
            }

            override fun onLost(
                network: Network
            ) {
                _internetAvailable.value =
                    detectInternetAvailability()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities:
                    NetworkCapabilities
            ) {
                _internetAvailable.value =
                    networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    ) &&
                    networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
            }
        }

    init {
        startMonitoring()
    }

    fun startMonitoring() {

        if (monitoringStarted) {
            return
        }

        monitoringStarted = true

        registerBluetoothReceiver()
        registerConnectivityCallback()

        /*
         * Keep the application-level state synchronized with
         * each underlying BLE component. Separate collectors
         * are intentional here: they avoid relying on a large
         * combine overload and keep this code compatible with
         * the coroutines version already used by the project.
         */
        scope.launch {
            bleAdvertiser.state.collect {
                publishState()
            }
        }

        scope.launch {
            bleAdvertiser.errorCode.collect {
                publishState()
            }
        }

        scope.launch {
            bleScanner.state.collect {
                publishState()
            }
        }

        scope.launch {
            bleScanner.errorCode.collect {
                publishState()
            }
        }

        scope.launch {
            bleScanner.peers.collect {
                publishState()
            }
        }

        scope.launch {
            bleScanner.scanResultCount.collect {
                publishState()
            }
        }

        scope.launch {
            bleScanner.lastResultAt.collect {
                publishState()
            }
        }

        scope.launch {
            while (true) {
                bleScanner.expireStalePeers()
                delay(1_000L)
            }
        }

        /*
         * Publish initial state after registration.
         */
        publishState()
    }

    fun refresh() {

        _permissionsGranted.value =
            hasRequiredPermissions()

        refreshBluetoothState()

        _internetAvailable.value =
            detectInternetAvailability()

        publishState()

        if (
            _permissionsGranted.value &&
            _bluetoothState.value ==
                BluetoothState.ON &&
            _networkEnabled.value
        ) {
            startAdvertiserIfNeeded()

            if (_discoveryRequested.value) {
                bleScanner.startScanning()
            }
        }
    }

    fun enableNetwork() {

        _permissionsGranted.value =
            hasRequiredPermissions()

        _networkEnabled.value = true

        if (
            _permissionsGranted.value &&
            _bluetoothState.value ==
                BluetoothState.ON
        ) {
            startAdvertiserIfNeeded()
        }

        publishState()
    }

    fun startDiscovery() {

        _permissionsGranted.value =
            hasRequiredPermissions()

        _networkEnabled.value = true
        _discoveryRequested.value = true

        publishState()

        if (
            !_permissionsGranted.value ||
            !isBleSupported()
        ) {
            return
        }

        if (
            _bluetoothState.value !=
                BluetoothState.ON
        ) {
            return
        }

        startAdvertiserIfNeeded()
        bleScanner.startScanning()
    }

    fun stopDiscovery() {

        _discoveryRequested.value = false

        bleScanner.stopScanning(
            clearPeers = true
        )

        publishState()
    }

    fun disableNetwork() {

        _networkEnabled.value = false
        _discoveryRequested.value = false

        bleScanner.stopScanning(
            clearPeers = true
        )

        bleAdvertiser.stopAdvertising()

        publishState()
    }

    fun clearPeers() {
        bleScanner.clearPeers()
        publishState()
    }

    fun refreshBluetoothState() {

        val previous =
            _bluetoothState.value

        val current =
            detectBluetoothState()

        _bluetoothState.value = current

        if (
            current !=
                BluetoothState.ON
        ) {

            bleScanner.stopScanning(
                clearPeers = true
            )

            bleAdvertiser.stopAdvertising()

        } else if (
            previous !=
                BluetoothState.ON
        ) {

            _permissionsGranted.value =
                hasRequiredPermissions()

            if (
                _networkEnabled.value &&
                _permissionsGranted.value
            ) {

                startAdvertiserIfNeeded()

                if (
                    _discoveryRequested.value
                ) {
                    bleScanner.startScanning()
                }
            }
        }

        publishState()
    }

    fun hasRequiredBlePermissions():
        Boolean {
        return hasRequiredPermissions()
    }

    private fun startAdvertiserIfNeeded() {

        if (
            bleAdvertiser.state.value ==
                BleOperationState.ACTIVE ||
            bleAdvertiser.state.value ==
                BleOperationState.STARTING
        ) {
            return
        }

        bleAdvertiser.startAdvertising(
            identityStore.getIdentity()
        )
    }

    private fun publishState() {

        _state.value =
            NetworkState(
                bluetoothState =
                    _bluetoothState.value,
                bleSupported =
                    isBleSupported(),
                permissionsGranted =
                    _permissionsGranted.value,
                internetAvailable =
                    _internetAvailable.value,
                networkEnabled =
                    _networkEnabled.value,
                discoveryRequested =
                    _discoveryRequested.value,
                advertisingState =
                    bleAdvertiser.state.value,
                scanningState =
                    bleScanner.state.value,
                advertisingErrorCode =
                    bleAdvertiser.errorCode.value,
                scanningErrorCode =
                    bleScanner.errorCode.value,
                peers =
                    bleScanner.peers.value,
                scanResultCount =
                    bleScanner.scanResultCount.value,
                lastScanResultAt =
                    bleScanner.lastResultAt.value
            )
    }

    private fun registerBluetoothReceiver() {

        val filter =
            IntentFilter(
                BluetoothAdapter.ACTION_STATE_CHANGED
            )

        if (Build.VERSION.SDK_INT >= 33) {

            appContext.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                bluetoothReceiver,
                filter
            )
        }
    }

    private fun registerConnectivityCallback() {

        try {
            connectivityManager
                .registerDefaultNetworkCallback(
                    connectivityCallback
                )
        } catch (_: Exception) {
            /*
             * Internet state still has an initial synchronous
             * value from detectInternetAvailability().
             */
        }
    }

    private fun detectBluetoothState():
        BluetoothState {

        if (!isBleSupported()) {
            return BluetoothState.UNSUPPORTED
        }

        val adapter =
            bluetoothManager?.adapter
                ?: return BluetoothState.UNSUPPORTED

        return try {

            if (adapter.isEnabled) {
                BluetoothState.ON
            } else {
                BluetoothState.OFF
            }

        } catch (
            _: SecurityException
        ) {

            BluetoothState.OFF
        }
    }

    private fun isBleSupported():
        Boolean {

        return packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE
        )
    }

    private fun hasRequiredPermissions():
        Boolean {

        val permissions =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
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

        return permissions.all { permission ->

            ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun detectInternetAvailability():
        Boolean {

        val network =
            connectivityManager.activeNetwork
                ?: return false

        val capabilities =
            connectivityManager
                .getNetworkCapabilities(network)
                ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) &&
            capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
    }

    companion object {

        @Volatile
        private var INSTANCE:
            NetworkManager? = null

        fun getInstance(
            context: Context
        ): NetworkManager {

            return INSTANCE
                ?: synchronized(this) {

                    INSTANCE
                        ?: NetworkManager(
                            context.applicationContext
                        ).also {
                            INSTANCE = it
                        }
                }
        }
    }
}
