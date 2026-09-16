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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class NetworkManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recoveryMutex = Mutex()

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
    private val _runtimeEvents = MutableSharedFlow<BleRuntimeEvent>(extraBufferCapacity = 16)

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()
    val blePermissions: StateFlow<BlePermissionState> = _blePermissions.asStateFlow()
    val runtimeGeneration: StateFlow<Long> = _runtimeGeneration.asStateFlow()
    val runtimeEvents = _runtimeEvents.asSharedFlow()

    @Volatile private var runtimeStarted = false
    private val runtimeJobs = mutableListOf<Job>()
    private var expiryJob: Job? = null
    private var recoveryJob: Job? = null
    private var bluetoothReceiverRegistered = false
    private var connectivityCallbackRegistered = false
    @Volatile private var gattStarter: (suspend () -> Boolean)? = null
    @Volatile private var gattStopper: (() -> Unit)? = null
    @Volatile private var transportWasAvailable = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.ERROR) refreshBluetoothState()
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

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _internetAvailable.value =
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            publishState()
        }
    }

    @Synchronized
    fun startRuntime() {
        if (runtimeStarted) {
            refresh()
            return
        }

        runtimeStarted = true
        _networkEnabled.value = true
        _discoveryRequested.value = true
        _initializationState.value = InitializationState.CHECKING_PERMISSIONS
        registerBluetoothReceiver()
        registerConnectivityCallback()

        runtimeJobs += scope.launch {
            bleAdvertiser.state.collect { state ->
                publishState()
                if (state == BleOperationState.ERROR && isBleRuntimeUsable()) {
                    requestRecovery()
                }
            }
        }

        runtimeJobs += scope.launch {
            bleAdvertiser.errorCode.collect { publishState() }
        }

        runtimeJobs += scope.launch {
            bleScanner.state.collect { state ->
                publishState()
                if (state == BleOperationState.ERROR && isBleRuntimeUsable()) {
                    requestRecovery()
                }
            }
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
            _gattServerReady.collect { ready ->
                publishState()
                if (!ready && isBleRuntimeUsable()) {
                    requestRecovery()
                }
            }
        }

        runtimeJobs += scope.launch {
            identityStore.identity.drop(1).collect { identity ->
                if (IdentityStore.getInstance(appContext).isNicknameConfigured()) {
                    bleAdvertiser.stopAdvertising()
                    requestRecovery()
                } else {
                    markTransportUnavailable(BleRuntimeEvent.TRANSPORT_UNAVAILABLE)
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
        cancelRecovery()
        incrementGeneration()
        markTransportUnavailable(BleRuntimeEvent.TRANSPORT_UNAVAILABLE)

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
        gattStopper?.invoke()

        _gattServerReady.value = false
        _initializationState.value = InitializationState.BOOTSTRAPPING
        publishState()
    }

    fun refresh() {
        _initializationState.value =
            if (!runtimeStarted) {
                InitializationState.BOOTSTRAPPING
            } else {
                InitializationState.CHECKING_PERMISSIONS
            }

        _blePermissions.value = PermissionManager.ble(appContext)
        _bluetoothState.value = detectBluetoothState()
        _internetAvailable.value = detectInternetAvailability()

        reconcileRuntime()
        updateInitializationState()
        publishState()
    }

    fun setGattLifecycle(
        start: suspend () -> Boolean,
        stop: () -> Unit
    ) {
        gattStarter = start
        gattStopper = stop

        if (runtimeStarted) {
            refresh()
        }
    }

    fun setGattServerReady(ready: Boolean) {
        _gattServerReady.value = ready
        updateInitializationState()
        publishState()
    }

    fun clearPeers() {
        bleScanner.clearPeers()
        publishState()
    }

    fun hasRequiredBlePermissions(): Boolean = _blePermissions.value.allGranted

    fun currentBlePermissions(): BlePermissionState =
        PermissionManager.ble(appContext)

    fun refreshBluetoothState() {
        val previous = _bluetoothState.value
        val current = detectBluetoothState()

        if (current != previous) {
            when (current) {
                BluetoothState.TURNING_ON,
                BluetoothState.TURNING_OFF,
                BluetoothState.OFF,
                BluetoothState.ERROR -> {
                    incrementGeneration()
                    _gattServerReady.value = false
                    cancelRecovery()
                    bleScanner.stopScanning(clearPeers = true)
                    bleAdvertiser.stopAdvertising()
                    gattStopper?.invoke()
                    markTransportUnavailable(BleRuntimeEvent.TRANSPORT_UNAVAILABLE)
                }

                BluetoothState.ON -> {
                    incrementGeneration()
                    _gattServerReady.value = false
                    cancelRecovery()
                    bleScanner.stopScanning(clearPeers = true)
                    bleAdvertiser.stopAdvertising()
                    gattStopper?.invoke()
                }

                BluetoothState.UNSUPPORTED -> {
                    incrementGeneration()
                    _gattServerReady.value = false
                    cancelRecovery()
                    bleScanner.stopScanning(clearPeers = true)
                    bleAdvertiser.stopAdvertising()
                    gattStopper?.invoke()
                    markTransportUnavailable(BleRuntimeEvent.TRANSPORT_UNAVAILABLE)
                }
            }
        }

        _bluetoothState.value = current

        if (!runtimeStarted) {
            updateInitializationState()
            publishState()
            return
        }

        reconcileRuntime()
        updateInitializationState()
        publishState()
    }

    private fun incrementGeneration() {
        _runtimeGeneration.value = _runtimeGeneration.value + 1L
    }

    private fun reconcileRuntime() {
        _blePermissions.value = PermissionManager.ble(appContext)

        val supported = isBleSupported()
        val permissions = _blePermissions.value
        val bluetoothReady = _bluetoothState.value == BluetoothState.ON

        when {
            !supported ||
                !permissions.allGranted ||
                !bluetoothReady ||
                !identityStore.isNicknameConfigured() -> {

                _gattServerReady.value = false
                cancelRecovery()
                bleScanner.stopScanning(clearPeers = true)
                bleAdvertiser.stopAdvertising()
                gattStopper?.invoke()
                markTransportUnavailable(BleRuntimeEvent.TRANSPORT_UNAVAILABLE)
            }

            runtimeStarted && _networkEnabled.value -> {
                requestRecovery()
            }
        }
    }

    private fun requestRecovery() {
        if (!isBleRuntimeUsable()) return
        if (recoveryJob?.isActive == true) return

        if (
            _gattServerReady.value &&
            bleAdvertiser.state.value == BleOperationState.ACTIVE &&
            bleScanner.state.value == BleOperationState.ACTIVE
        ) {
            return
        }

        recoveryJob = scope.launch {
            recoveryMutex.withLock {
                var retryAttempt = 0

                while (runtimeStarted && isBleRuntimeUsable()) {
                    when (recoverRuntimeOnce()) {
                        RecoveryOutcome.READY -> return@withLock
                        RecoveryOutcome.ABORT -> return@withLock
                        RecoveryOutcome.RETRY -> {
                            _initializationState.value = InitializationState.RECOVERING
                            publishState()

                            val delayMs = recoveryBackoff(retryAttempt)
                            retryAttempt = (retryAttempt + 1).coerceAtMost(6)
                            delay(delayMs)
                        }
                    }
                }
            }
        }
    }

    private suspend fun waitForOperationState(
    stateProvider: () -> BleOperationState,
    success: BleOperationState,
    failure: BleOperationState,
    timeoutMs: Long
): Boolean {
    if (stateProvider() == success) return true
    if (stateProvider() == failure) return false

    val completed = withTimeoutOrNull(timeoutMs) {
        while (true) {
            when (stateProvider()) {
                success -> break
                failure -> return@withTimeoutOrNull false
                else -> delay(50L)
            }
        }
        true
    }

    return completed ?: false
}

    private suspend fun recoverRuntimeOnce(): RecoveryOutcome {
        val targetGeneration = _runtimeGeneration.value

        if (!generationStillUsable(targetGeneration)) {
            return RecoveryOutcome.ABORT
        }

        // Give Android a short handoff window after STATE_ON, and also make
        // repeated runtime recovery gentler on the Bluetooth stack.
        delay(350L)

        if (!generationStillUsable(targetGeneration)) {
            return RecoveryOutcome.ABORT
        }

        val starter = gattStarter ?: return RecoveryOutcome.RETRY

        if (!_gattServerReady.value) {
            _initializationState.value = InitializationState.STARTING_GATT
            publishState()

            val gattReady = runCatching { starter() }.getOrDefault(false)

            if (!gattReady || !generationStillUsable(targetGeneration)) {
                _gattServerReady.value = false

                return if (generationStillUsable(targetGeneration)) {
                    RecoveryOutcome.RETRY
                } else {
                    RecoveryOutcome.ABORT
                }
            }

            _gattServerReady.value = true
            publishState()

            // Outgoing GATT transport is usable as soon as the local GATT
            // server is actually ready. Discovery can recover independently.
            markTransportAvailable()
        } else if (!transportWasAvailable) {
            markTransportAvailable()
        }

        if (!generationStillUsable(targetGeneration)) {
            return RecoveryOutcome.ABORT
        }

        val advertiserReady =
            if (bleAdvertiser.state.value == BleOperationState.ACTIVE) {
                true
            } else {
                _initializationState.value = InitializationState.STARTING_DISCOVERY
                publishState()

                if (bleAdvertiser.state.value == BleOperationState.ERROR) {
                    bleAdvertiser.stopAdvertising()
                    delay(300L)
                }

                bleAdvertiser.startAdvertising(identityStore.getIdentity())

                waitForOperationState(
                    stateProvider = { bleAdvertiser.state.value },
                    success = BleOperationState.ACTIVE,
                    failure = BleOperationState.ERROR,
                    timeoutMs = ADVERTISER_START_WAIT_MS
                )

                if (bleAdvertiser.state.value == BleOperationState.ACTIVE) {
                    true
                } else if (isRetryableAdvertiserError(bleAdvertiser.errorCode.value)) {
                    // Also clean up a STARTING session that never produced a
                    // callback within the bounded wait window.
                    bleAdvertiser.stopAdvertising()
                    false
                } else {
                    return RecoveryOutcome.ABORT
                }
            }

        if (!advertiserReady) {
            return RecoveryOutcome.RETRY
        }

        if (!generationStillUsable(targetGeneration)) {
            return RecoveryOutcome.ABORT
        }

        val scannerReady =
            if (bleScanner.state.value == BleOperationState.ACTIVE) {
                true
            } else {
                _initializationState.value = InitializationState.STARTING_DISCOVERY
                publishState()

                if (bleScanner.state.value == BleOperationState.ERROR) {
                    bleScanner.stopScanning(clearPeers = true)
                    delay(200L)
                }

                bleScanner.startScanning()

                waitForOperationState(
                    stateProvider = { bleScanner.state.value },
                    success = BleOperationState.ACTIVE,
                    failure = BleOperationState.ERROR,
                    timeoutMs = SCANNER_START_WAIT_MS
                )

                if (bleScanner.state.value == BleOperationState.ACTIVE) {
                    true
                } else {
                    // Do not leave a hung STARTING scanner behind for the next
                    // recovery generation.
                    bleScanner.stopScanning(clearPeers = true)
                    false
                }
            }

        if (!scannerReady) {
            return RecoveryOutcome.RETRY
        }

        if (!generationStillUsable(targetGeneration)) {
            return RecoveryOutcome.ABORT
        }

        _initializationState.value = InitializationState.READY
        publishState()

        return RecoveryOutcome.READY
    }

    private enum class RecoveryOutcome {
        READY,
        RETRY,
        ABORT
    }

    private fun generationStillUsable(targetGeneration: Long): Boolean =
        runtimeStarted &&
            _networkEnabled.value &&
            _runtimeGeneration.value == targetGeneration &&
            _bluetoothState.value == BluetoothState.ON &&
            _blePermissions.value.allGranted &&
            isBleSupported() &&
            identityStore.isNicknameConfigured()

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun markTransportUnavailable(event: BleRuntimeEvent) {
        if (!transportWasAvailable) return
        transportWasAvailable = false
        _runtimeEvents.tryEmit(event)
    }

    private fun markTransportAvailable() {
        if (transportWasAvailable) return
        transportWasAvailable = true
        _runtimeEvents.tryEmit(BleRuntimeEvent.TRANSPORT_AVAILABLE)
    }

    private fun updateInitializationState() {
        _initializationState.value = when {
            !isBleSupported() ->
                InitializationState.DEGRADED

            !_blePermissions.value.allGranted ->
                InitializationState.PERMISSION_REQUIRED

            _bluetoothState.value == BluetoothState.OFF ||
                _bluetoothState.value == BluetoothState.TURNING_OFF ->
                InitializationState.BLUETOOTH_OFF

            _bluetoothState.value != BluetoothState.ON ->
                InitializationState.RECOVERING

            !runtimeStarted ->
                InitializationState.BOOTSTRAPPING

            bleScanner.state.value == BleOperationState.ERROR ||
                bleAdvertiser.state.value == BleOperationState.ERROR ->
                InitializationState.DEGRADED

            !_gattServerReady.value ->
                if (recoveryJob?.isActive == true) {
                    InitializationState.RECOVERING
                } else if (_runtimeGeneration.value == 0L) {
                    InitializationState.STARTING_GATT
                } else {
                    InitializationState.RECOVERING
                }

            recoveryJob?.isActive == true ->
                InitializationState.RECOVERING

            bleScanner.state.value != BleOperationState.ACTIVE ||
                bleAdvertiser.state.value != BleOperationState.ACTIVE ->
                InitializationState.STARTING_DISCOVERY

            else ->
                InitializationState.READY
        }
    }

    private fun isBleRuntimeUsable(): Boolean =
        runtimeStarted &&
            _blePermissions.value.allGranted &&
            _bluetoothState.value == BluetoothState.ON &&
            isBleSupported() &&
            identityStore.isNicknameConfigured()

    private fun buildRuntimeState(): BleRuntimeState = when {
        !isBleSupported() ->
            BleRuntimeState.UNSUPPORTED

        !_blePermissions.value.allGranted ->
            BleRuntimeState.PERMISSION_REQUIRED

        _bluetoothState.value == BluetoothState.OFF ||
            _bluetoothState.value == BluetoothState.TURNING_OFF ->
            BleRuntimeState.BLUETOOTH_OFF

        _bluetoothState.value != BluetoothState.ON ->
            BleRuntimeState.RECOVERING

        !runtimeStarted || !_networkEnabled.value ->
            BleRuntimeState.STARTING

        _gattServerReady.value &&
            bleScanner.state.value == BleOperationState.ACTIVE &&
            bleAdvertiser.state.value == BleOperationState.ACTIVE ->
            BleRuntimeState.READY

        recoveryJob?.isActive == true ->
            BleRuntimeState.RECOVERING

        bleScanner.state.value == BleOperationState.ERROR ||
            bleAdvertiser.state.value == BleOperationState.ERROR ->
            BleRuntimeState.DEGRADED

        _runtimeGeneration.value > 0L ->
            BleRuntimeState.RECOVERING

        else ->
            BleRuntimeState.STARTING
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

    private fun publishState() {
        updateInitializationState()
        _state.value = buildState()
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                bluetoothReceiver,
                filter
            )
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
        if (!isBleSupported()) {
            return BluetoothState.UNSUPPORTED
        }

        val adapter = bluetoothManager?.adapter
            ?: return BluetoothState.UNSUPPORTED

        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_ON ->
                    BluetoothState.ON

                BluetoothAdapter.STATE_OFF ->
                    BluetoothState.OFF

                BluetoothAdapter.STATE_TURNING_ON ->
                    BluetoothState.TURNING_ON

                BluetoothAdapter.STATE_TURNING_OFF ->
                    BluetoothState.TURNING_OFF

                else ->
                    BluetoothState.ERROR
            }
        } catch (_: SecurityException) {
            BluetoothState.ERROR
        }
    }

    private fun isBleSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private fun detectInternetAvailability(): Boolean {
        val network = connectivityManager.activeNetwork
            ?: return false

        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isRetryableAdvertiserError(code: Int?): Boolean =
        when (code) {
            null,
            BleAdvertiser.ERROR_ADVERTISER_UNAVAILABLE ->
                true

            BleAdvertiser.ERROR_PERMISSION,
            BleAdvertiser.ERROR_BLUETOOTH_UNAVAILABLE,
            BleAdvertiser.ERROR_BLUETOOTH_DISABLED,
            BleAdvertiser.ERROR_ADVERTISING_NOT_SUPPORTED,
            BleAdvertiser.ERROR_INVALID_CONFIGURATION ->
                false

            else ->
                true
        }

    private fun recoveryBackoff(attempt: Int): Long =
        when (attempt.coerceIn(0, 6)) {
            0 -> 750L
            1 -> 1_500L
            2 -> 3_000L
            3 -> 5_000L
            4 -> 10_000L
            5 -> 20_000L
            else -> 30_000L
        }

    companion object {
        private const val SCANNER_START_WAIT_MS = 1_500L
        private const val ADVERTISER_START_WAIT_MS = 2_000L

        @Volatile
        private var INSTANCE: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }
}