package com.hybridmesh.relay.messaging.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import com.hybridmesh.relay.messaging.protocol.GattPacketCodec
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.notifications.MessagingNotificationCoordinator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

class BleGattServer(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val identityStore = IdentityStore.getInstance(appContext)
    private val repository = MessagingRepository.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationCoordinator = MessagingNotificationCoordinator.getInstance(appContext)

    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var readiness: CompletableDeferred<Boolean>? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var identityCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var serverGeneration: Long = 0L

    private val subscribedDevices = ConcurrentHashMap.newKeySet<String>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()

    @Volatile
    var lastAckDeliverySucceeded: Boolean? = null
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (server != null) return isReady
        val currentGeneration = ++serverGeneration
        val manager = bluetoothManager ?: return false

        return try {
            val opened = manager.openGattServer(appContext, callbackFor(currentGeneration)) ?: return false
            val readySignal = CompletableDeferred<Boolean>()
            readiness = readySignal
            isReady = false
            server = opened

            val service = BluetoothGattService(
                BleGattConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            rxCharacteristic = BluetoothGattCharacteristic(
                BleGattConstants.RX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            txCharacteristic = BluetoothGattCharacteristic(
                BleGattConstants.TX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            identityCharacteristic = BluetoothGattCharacteristic(
                BleGattConstants.IDENTITY_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            txCharacteristic?.addDescriptor(
                BluetoothGattDescriptor(
                    BleGattConstants.CLIENT_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )

            val rx = rxCharacteristic ?: run { opened.close(); return false }
            val tx = txCharacteristic ?: run { opened.close(); return false }
            val identity = identityCharacteristic ?: run { opened.close(); return false }

            service.addCharacteristic(rx)
            service.addCharacteristic(tx)
            service.addCharacteristic(identity)

            if (!opened.addService(service)) {
                readiness?.complete(false)
                readiness = null
                opened.close()
                server = null
                rxCharacteristic = null
                txCharacteristic = null
                identityCharacteristic = null
                isReady = false
                return false
            }

            true
        } catch (_: SecurityException) {
            readiness?.complete(false)
            readiness = null
            runCatching { server?.close() }
            server = null
            rxCharacteristic = null
            txCharacteristic = null
            identityCharacteristic = null
            false
        } catch (_: Exception) {
            readiness?.complete(false)
            readiness = null
            runCatching { server?.close() }
            server = null
            rxCharacteristic = null
            txCharacteristic = null
            identityCharacteristic = null
            false
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 5_000L): Boolean {
        if (isReady) return true
        val signal = readiness ?: return false
        return withTimeoutOrNull(timeoutMs) { signal.await() } == true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        serverGeneration += 1L
        isReady = false
        readiness?.complete(false)
        readiness = null
        assemblies.clear()
        subscribedDevices.clear()
        lastAckDeliverySucceeded = null
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        rxCharacteristic = null
        txCharacteristic = null
        identityCharacteristic = null
    }

    private fun callbackFor(callbackGeneration: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService
        ) {
            if (callbackGeneration != serverGeneration) return
            if (service.uuid != BleGattConstants.SERVICE_UUID) return

            val success = status == BluetoothGatt.GATT_SUCCESS
            isReady = success && server != null
            readiness?.complete(success)
            if (!success) {
                readiness = null
                runCatching { server?.close() }
                server = null
                rxCharacteristic = null
                txCharacteristic = null
                identityCharacteristic = null
            } else {
                readiness = null
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (callbackGeneration != serverGeneration) return
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                val prefix = "${safeAddress(device)}|"
                assemblies.keys.removeIf { it.startsWith(prefix) }
                subscribedDevices.remove(safeAddress(device))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (callbackGeneration != serverGeneration) return
            var status = BluetoothGatt.GATT_SUCCESS
            if (preparedWrite || offset != 0 || descriptor.uuid != BleGattConstants.CLIENT_CONFIG_UUID) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscribedDevices.add(safeAddress(device))
            } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                subscribedDevices.remove(safeAddress(device))
            } else {
                status = BluetoothGatt.GATT_FAILURE
            }

            if (responseNeeded) {
                server?.sendResponse(device, requestId, status, offset, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (callbackGeneration != serverGeneration) return
            if (characteristic.uuid != BleGattConstants.IDENTITY_CHARACTERISTIC_UUID) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null
                )
                return
            }

            val identityBytes = buildIdentityPayload()
            if (offset < 0 || offset > identityBytes.size) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET,
                    offset,
                    null
                )
                return
            }

            val end = minOf(
                identityBytes.size,
                offset + BleGattConstants.DEFAULT_ATT_PAYLOAD_BYTES
            )
            server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                identityBytes.copyOfRange(offset, end)
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (callbackGeneration != serverGeneration) return
            val supported = characteristic.uuid == BleGattConstants.RX_CHARACTERISTIC_UUID &&
                !preparedWrite &&
                offset == 0

            if (!supported) {
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                }
                return
            }

            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }

            handleIncoming(device, value, callbackGeneration)
        }
    }

    private fun handleIncoming(device: BluetoothDevice, bytes: ByteArray, callbackGeneration: Long) {
        if (callbackGeneration != serverGeneration) return
        val frame = GattPacketCodec.decode(bytes) as? GattPacketCodec.DataFrame ?: return
        if (frame.chunkCount !in 1..GattPacketCodec.MAX_CHUNKS) return

        val localNodeId = identityStore.getIdentity().nodeId
        val address = safeAddress(device)
        val key = "$address|${frame.transferId}"

        val assembly = assemblies.computeIfAbsent(key) {
            Assembly(
                transferId = frame.transferId,
                chunkCount = frame.chunkCount
            )
        }

        if (assembly.chunkCount != frame.chunkCount) {
            assemblies.remove(key)
            sendAck(device, frame.transferId, accepted = false)
            return
        }

        synchronized(assembly) {
            if (assembly.completed) return
            assembly.chunks[frame.chunkIndex] = frame.payload
            if (assembly.chunks.size != assembly.chunkCount) return
            assembly.completed = true
        }

        val combined = ByteArray(assembly.chunks.values.sumOf { it.size })
        var cursor = 0
        for (index in 0 until assembly.chunkCount) {
            val chunk = assembly.chunks[index] ?: run {
                assembly.completed = false
                return
            }
            chunk.copyInto(combined, cursor)
            cursor += chunk.size
        }

        val envelope = GattPacketCodec.decodeMessageEnvelope(combined)
        if (envelope == null || !envelope.recipientNodeId.equals(localNodeId, ignoreCase = true)) {
            assemblies.remove(key)
            sendAck(device, frame.transferId, accepted = false)
            return
        }

        val messageId = GattPacketCodec.messageId(envelope.senderNodeId, frame.transferId)
        val typeName = decodeMessageType(envelope.messageType)

        scope.launch {
            try {
                if (callbackGeneration != serverGeneration) return@launch
                val existing = repository.getById(messageId)
                if (existing == null) {
                    repository.addIncoming(
                        MessageRecordEntity(
                            messageId = messageId,
                            senderNodeId = envelope.senderNodeId,
                            recipientNodeId = envelope.recipientNodeId,
                            content = envelope.content,
                            createdAt = envelope.createdAt,
                            messageType = typeName,
                            status = DeliveryStatus.DELIVERED.name,
                            lastTransport = "BLE",
                            deliveredAt = System.currentTimeMillis(),
                            attemptCount = 0,
                            nextAttemptAt = null,
                            lastError = null
                        )
                    )
                    val peer = repository.getPeer(envelope.senderNodeId)
                    notificationCoordinator.notifyIncoming(
                        peerNodeId = envelope.senderNodeId,
                        displayName = peer?.displayName?.takeIf { it.isNotBlank() && !it.equals(envelope.senderNodeId, true) } ?: envelope.senderNodeId,
                        body = if (typeName == MessageType.LOCATION.name) "Shared a location" else envelope.content
                    )
                }
                if (callbackGeneration != serverGeneration) return@launch
                sendAck(device, frame.transferId, accepted = true)
            } catch (_: Exception) {
                sendAck(device, frame.transferId, accepted = false)
            } finally {
                assemblies.remove(key)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(
        device: BluetoothDevice,
        transferId: Long,
        accepted: Boolean
    ): Boolean {
        if (!subscribedDevices.contains(safeAddress(device))) return false
        val characteristic = txCharacteristic ?: return false
        val currentServer = server ?: return false
        val value = GattPacketCodec.encodeAck(transferId, accepted)

        val success = try {
            if (Build.VERSION.SDK_INT >= 33) {
                currentServer.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false,
                    value
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                currentServer.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        lastAckDeliverySucceeded = success
        return success
    }

    private fun buildIdentityPayload(): ByteArray {
        val identity = identityStore.getIdentity()
        val name = identity.deviceName
            .trim()
            .toByteArray(Charsets.UTF_8)
            .truncateUtf8(BleGattConstants.IDENTITY_NAME_MAX_BYTES)

        return ByteBuffer.allocate(1 + 1 + name.size + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(BleGattConstants.IDENTITY_VERSION)
                put(name.size.toByte())
                put(name)
                put(if (identity.deviceType.name == "RELAY") 2 else 1)
            }
            .array()
    }

    private fun ByteArray.truncateUtf8(maxBytes: Int): ByteArray {
        if (size <= maxBytes) return this
        var end = maxBytes
        while (end > 0 && (this[end - 1].toInt() and 0xC0) == 0x80) end--
        return copyOf(end)
    }

    private fun decodeMessageType(value: Byte): String = when (value.toInt()) {
        1 -> MessageType.NORMAL.name
        2 -> MessageType.PRIORITY.name
        3 -> MessageType.EMERGENCY.name
        4 -> MessageType.LOCATION.name
        else -> MessageType.NORMAL.name
    }

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice): String =
        runCatching { device.address }.getOrDefault(device.hashCode().toString())

    private data class Assembly(
        val transferId: Long,
        val chunkCount: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var completed: Boolean = false
    )
}
