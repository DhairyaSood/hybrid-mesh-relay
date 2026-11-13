package com.hybridmesh.relay.messaging.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.hybridmesh.relay.diagnostics.MeshDbg
import com.hybridmesh.relay.permissions.PermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Phone <-> NEYRA-LORA BLE link state. */
sealed interface LoRaBridgeState {
    data object Disabled : LoRaBridgeState
    /** Not connected; [reason] says why (permission, Bluetooth off, backoff after a failure). */
    data class Waiting(val reason: String) : LoRaBridgeState
    data object Scanning : LoRaBridgeState
    data class Connecting(val address: String) : LoRaBridgeState
    data class Ready(val address: String, val mtu: Int) : LoRaBridgeState
}

sealed interface LoRaBridgeWriteResult {
    data class Written(val chunks: Int, val mtu: Int) : LoRaBridgeWriteResult
    data class Failed(val reason: String) : LoRaBridgeWriteResult
}

/** What LoRaBridgeTransport needs from the BLE link; faked in unit tests. */
interface LoRaBridgeLink {
    val state: StateFlow<LoRaBridgeState>
    suspend fun sendPacket(packet: ByteArray): LoRaBridgeWriteResult
}

private class BridgeLinkException(message: String) : Exception(message)

/**
 * BLE central for one NEYRA-LORA bridge. Scans only for service 7f6d2001 (never the
 * mesh discovery UUID), connects, requests MTU 247, subscribes to 7f6d2003 and keeps
 * the link up with backoff. All work runs on Dispatchers.IO; GATT callbacks only
 * complete deferreds. One GATT operation at a time: setup before Ready, writes after,
 * serialized by [writeMutex].
 */
@SuppressLint("MissingPermission")
class LoRaBridgeClient(
    context: Context,
    private val onPacket: suspend (bridgeAddress: String, packet: ByteArray) -> Unit
) : LoRaBridgeLink {
    private val appContext = context.applicationContext
    private val bluetoothManager by lazy { appContext.getSystemService(BluetoothManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val _state = MutableStateFlow<LoRaBridgeState>(LoRaBridgeState.Disabled)
    override val state: StateFlow<LoRaBridgeState> = _state.asStateFlow()

    private var runJob: Job? = null
    @Volatile private var session: Session? = null

    @Synchronized
    fun start() {
        if (runJob?.isActive == true) return
        MeshDbg.log("LORA", "bridge link start (scan service=${BleBridgeConstants.SERVICE_UUID})")
        runJob = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        val job = runJob ?: return
        runJob = null
        job.cancel()
        session?.close()
        session = null
        _state.value = LoRaBridgeState.Disabled
        MeshDbg.log("LORA", "bridge link stopped")
    }

    private suspend fun runLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val notReady = readinessFailure()
            if (notReady != null) {
                val wait = backoffMs(failures++)
                _state.value = LoRaBridgeState.Waiting(notReady)
                MeshDbg.throttled("lora-wait-$notReady", 60_000L, "LORA", "bridge waiting reason=$notReady retryInMs=$wait")
                delay(wait)
                continue
            }
            val reachedReady = try {
                runSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MeshDbg.log("LORA", "bridge session failed reason=${error.message ?: error.javaClass.simpleName}")
                false
            }
            failures = if (reachedReady) 0 else failures + 1
            val wait = backoffMs(failures)
            _state.value = LoRaBridgeState.Waiting("RECONNECT_BACKOFF retryInMs=$wait")
            MeshDbg.log("LORA", "bridge link down reconnectInMs=$wait consecutiveFailures=$failures")
            delay(wait)
        }
    }

    private fun backoffMs(failures: Int): Long =
        BleBridgeConstants.RECONNECT_BACKOFF_MS[failures.coerceIn(0, BleBridgeConstants.RECONNECT_BACKOFF_MS.lastIndex)]

    private fun readinessFailure(): String? {
        val permissions = PermissionManager.ble(appContext)
        val adapter = bluetoothManager?.adapter
        return when {
            !permissions.scanGranted || !permissions.connectGranted -> "BLE_PERMISSION_MISSING"
            adapter == null -> "BLE_NOT_SUPPORTED"
            !adapter.isEnabled -> "BLUETOOTH_OFF"
            else -> null
        }
    }

    /** One scan/connect/subscribe cycle. Returns after disconnect; true if the link reached Ready. */
    private suspend fun runSession(): Boolean {
        val device = scanForBridge() ?: throw BridgeLinkException("NO_BRIDGE_FOUND within ${BleBridgeConstants.SCAN_TIMEOUT_MS} ms")
        val address = device.address
        val s = Session(address)
        session = s
        try {
            _state.value = LoRaBridgeState.Connecting(address)
            MeshDbg.log("LORA", "bridge connecting addr=$address")
            s.gatt = device.connectGatt(appContext, false, s.callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BridgeLinkException("CONNECT_REJECTED")
            s.await(s.connected, BleBridgeConstants.CONNECT_TIMEOUT_MS, "CONNECT")
            MeshDbg.log("LORA", "bridge connected addr=$address")

            val gatt = s.requireGatt()
            if (gatt.requestMtu(BleBridgeConstants.TARGET_MTU)) {
                // MTU is optional: on timeout the 23-byte default is used.
                withTimeoutOrNull(BleBridgeConstants.SETUP_STEP_TIMEOUT_MS) { s.mtuChanged.await() }
            }
            MeshDbg.log(
                "LORA",
                "bridge mtu addr=$address mtu=${s.mtu} chunkData=${BleBridgeFraming.chunkDataBytes(s.mtu)}"
            )

            if (!gatt.discoverServices()) throw BridgeLinkException("DISCOVERY_REJECTED")
            s.await(s.servicesDiscovered, BleBridgeConstants.SETUP_STEP_TIMEOUT_MS, "SERVICE_DISCOVERY")
            val service = gatt.getService(BleBridgeConstants.SERVICE_UUID)
                ?: throw BridgeLinkException("SERVICE_MISSING ${BleBridgeConstants.SERVICE_UUID}")
            s.writeCharacteristic = service.getCharacteristic(BleBridgeConstants.WRITE_CHAR_UUID)
                ?: throw BridgeLinkException("WRITE_CHARACTERISTIC_MISSING")
            val notify = service.getCharacteristic(BleBridgeConstants.NOTIFY_CHAR_UUID)
                ?: throw BridgeLinkException("NOTIFY_CHARACTERISTIC_MISSING")
            subscribe(s, gatt, notify)

            _state.value = LoRaBridgeState.Ready(address, s.mtu)
            MeshDbg.log("LORA", "bridge ready addr=$address mtu=${s.mtu} notify=subscribed")
            s.disconnected.await()
            return true
        } finally {
            if (session === s) session = null
            synchronized(s.reassembler) { s.reassembler.onDisconnect() }?.let {
                MeshDbg.log("LORA", "rx drop addr=$address reason=$it")
            }
            s.close()
            MeshDbg.log("LORA", "bridge disconnected addr=$address status=${s.disconnectStatus}")
        }
    }

    private suspend fun subscribe(s: Session, gatt: BluetoothGatt, notify: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(notify, true)) throw BridgeLinkException("NOTIFY_ENABLE_REJECTED")
        val descriptor = notify.getDescriptor(BleBridgeConstants.CLIENT_CONFIG_UUID)
            ?: throw BridgeLinkException("CCCD_MISSING")
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!accepted) throw BridgeLinkException("CCCD_WRITE_REJECTED")
        s.await(s.notificationsEnabled, BleBridgeConstants.SETUP_STEP_TIMEOUT_MS, "SUBSCRIBE")
    }

    private suspend fun scanForBridge(): BluetoothDevice? {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: throw BridgeLinkException("SCANNER_UNAVAILABLE")
        _state.value = LoRaBridgeState.Scanning
        MeshDbg.log("LORA", "bridge scan start service=${BleBridgeConstants.SERVICE_UUID}")
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.complete(result.device)) {
                    MeshDbg.log(
                        "LORA",
                        "bridge found addr=${result.device.address} rssi=${result.rssi} " +
                            "name=${result.scanRecord?.deviceName ?: "-"}"
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(BridgeLinkException("SCAN_FAILED code=$errorCode"))
            }
        }
        // Only the bridge service UUID: the mesh discovery UUID is never scanned here.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleBridgeConstants.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        return try {
            withTimeoutOrNull(BleBridgeConstants.SCAN_TIMEOUT_MS) { found.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    override suspend fun sendPacket(packet: ByteArray): LoRaBridgeWriteResult = writeMutex.withLock {
        val s = session
        val ready = _state.value as? LoRaBridgeState.Ready
        val gatt = s?.gatt
        val characteristic = s?.writeCharacteristic
        if (s == null || ready == null || gatt == null || characteristic == null) {
            return LoRaBridgeWriteResult.Failed("NOT_CONNECTED")
        }
        if (packet.isEmpty() || packet.size > BleBridgeConstants.MAX_PACKET_BYTES) {
            return LoRaBridgeWriteResult.Failed("PACKET_SIZE bytes=${packet.size} max=${BleBridgeConstants.MAX_PACKET_BYTES}")
        }
        val chunks = BleBridgeFraming.split(packet, s.mtu)
        chunks.forEachIndexed { index, chunk ->
            val waiter = CompletableDeferred<Boolean>()
            s.writeWaiter = waiter
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            val chunkLabel = "chunk=${index + 1}/${chunks.size}"
            if (!accepted) {
                s.writeWaiter = null
                return LoRaBridgeWriteResult.Failed("WRITE_REJECTED $chunkLabel")
            }
            val ok = withTimeoutOrNull(BleBridgeConstants.WRITE_TIMEOUT_MS) {
                select<Boolean> {
                    waiter.onAwait { it }
                    s.disconnected.onAwait { false }
                }
            }
            if (ok != true) {
                s.writeWaiter = null
                return LoRaBridgeWriteResult.Failed(if (ok == null) "WRITE_TIMEOUT $chunkLabel" else "WRITE_FAILED $chunkLabel")
            }
        }
        LoRaBridgeWriteResult.Written(chunks.size, s.mtu)
    }

    private fun onNotification(s: Session, value: ByteArray) {
        val outcome = synchronized(s.reassembler) { s.reassembler.accept(value, System.currentTimeMillis()) }
        outcome.dropReason?.let { MeshDbg.log("LORA", "rx drop addr=${s.address} reason=$it") }
        val packet = outcome.packet ?: return
        MeshDbg.log("LORA", "rx packet addr=${s.address} bytes=${packet.size}")
        scope.launch {
            runCatching { onPacket(s.address, packet) }.onFailure {
                MeshDbg.log("LORA", "rx handler failed addr=${s.address} error=${it.javaClass.simpleName}")
            }
        }
    }

    private inner class Session(val address: String) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var mtu: Int = BleBridgeConstants.MIN_ATT_MTU
        @Volatile var writeCharacteristic: BluetoothGattCharacteristic? = null
        @Volatile var writeWaiter: CompletableDeferred<Boolean>? = null
        @Volatile var disconnectStatus: Int? = null
        val reassembler = BleBridgeReassembler()
        val connected = CompletableDeferred<Unit>()
        val mtuChanged = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val notificationsEnabled = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()

        fun requireGatt(): BluetoothGatt = gatt ?: throw BridgeLinkException("GATT_CLOSED")

        /** Waits for [step], failing fast if the link drops or the step times out. */
        suspend fun <T> await(deferred: CompletableDeferred<T>, timeoutMs: Long, step: String): T =
            withTimeoutOrNull(timeoutMs) {
                select<T> {
                    deferred.onAwait { it }
                    disconnected.onAwait { throw BridgeLinkException("DISCONNECTED_DURING_$step status=$disconnectStatus") }
                }
            } ?: throw BridgeLinkException("${step}_TIMEOUT after ${timeoutMs} ms")

        fun close() {
            disconnected.complete(Unit)
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            gatt = null
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                } else {
                    disconnectStatus = status
                    disconnected.complete(Unit)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) this@Session.mtu = mtu
                mtuChanged.complete(Unit)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesDiscovered.complete(Unit)
                } else {
                    servicesDiscovered.completeExceptionally(BridgeLinkException("SERVICE_DISCOVERY_FAILED status=$status"))
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != BleBridgeConstants.CLIENT_CONFIG_UUID) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationsEnabled.complete(Unit)
                } else {
                    notificationsEnabled.completeExceptionally(BridgeLinkException("CCCD_WRITE_FAILED status=$status"))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val waiter = writeWaiter
                writeWaiter = null
                waiter?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Deprecated in Android API")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return  // the value overload below handles API 33+
                if (characteristic.uuid != BleBridgeConstants.NOTIFY_CHAR_UUID) return
                @Suppress("DEPRECATION")
                onNotification(this@Session, characteristic.value ?: return)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != BleBridgeConstants.NOTIFY_CHAR_UUID) return
                onNotification(this@Session, value)
            }
        }
    }
}
