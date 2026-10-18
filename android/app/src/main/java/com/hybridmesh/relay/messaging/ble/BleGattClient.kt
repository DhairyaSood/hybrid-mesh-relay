package com.hybridmesh.relay.messaging.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.messaging.model.GattTransportError
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.messaging.model.GattTransportState
import com.hybridmesh.relay.messaging.protocol.GattPacketCodec
import com.hybridmesh.relay.messaging.attachment.AttachmentFileStore
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.attachment.BleAttachmentFrameCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class BleGattClient(context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun sendMessage(
        device: BluetoothDevice,
        record: MessageRecordEntity,
        runtimeGeneration: Long,
        isRuntimeGenerationCurrent: () -> Boolean,
        onState: (GattTransportSnapshot) -> Unit = {}
    ): GattSendResult {
        val packet = com.hybridmesh.relay.messaging.mesh.MeshPacket(
            packetId = com.hybridmesh.relay.messaging.mesh.MeshPacketCodec.stableDataPacketId(record.messageId),
            messageId = record.messageId,
            originNodeId = record.senderNodeId,
            destinationNodeId = record.recipientNodeId,
            createdAt = record.createdAt,
            expiresAt = record.createdAt +
                com.hybridmesh.relay.messaging.mesh.MeshEngine.MESSAGE_LIFETIME_MS,
            ttl = com.hybridmesh.relay.messaging.mesh.MeshEngine.INITIAL_TTL,
            hopCount = 0,
            packetType = com.hybridmesh.relay.messaging.mesh.MeshPacket.PacketType.DATA,
            messageType = record.messageType,
            content = record.content
        )
        return sendPayload(
            device = device,
            payload = com.hybridmesh.relay.messaging.mesh.MeshPacketCodec.encode(packet),
            messageId = record.messageId,
            transferId = com.hybridmesh.relay.messaging.mesh.MeshPacketCodec.newTransferId(),
            runtimeGeneration = runtimeGeneration,
            isRuntimeGenerationCurrent = isRuntimeGenerationCurrent,
            onState = onState
        )
    }

    /** Sends an already-encoded network payload over the existing reliable GATT transport. */
    @SuppressLint("MissingPermission")
    suspend fun sendPayload(
        device: BluetoothDevice,
        payload: ByteArray,
        messageId: String?,
        transferId: Long = UUID.randomUUID().leastSignificantBits,
        runtimeGeneration: Long,
        isRuntimeGenerationCurrent: () -> Boolean,
        onState: (GattTransportSnapshot) -> Unit = {}
    ): GattSendResult {
        val session = ClientSession(
            address = safeAddress(device),
            messageId = messageId,
            runtimeGeneration = runtimeGeneration,
            isRuntimeGenerationCurrent = isRuntimeGenerationCurrent,
            publish = onState
        )

        val gatt = try {
            connect(device, session)
        } catch (_: CancellationException) {
            throw CancellationException()
        }

        if (gatt == null) {
            return GattSendResult.Failed(
                error = session.error,
                snapshot = session.snapshot()
            )
        }

        try {
            session.requireRuntime()
            session.transition(GattTransportState.DISCOVERING_SERVICES)
            discoverServices(gatt, session)

            session.transition(GattTransportState.SETTING_UP_NOTIFICATIONS)
            enableNotifications(gatt, session)

            session.transition(GattTransportState.NEGOTIATING_MTU)
            val mtu = negotiateMtu(gatt, session)
            session.transition(GattTransportState.READY)
            val payloadSize = GattPacketCodec.safePayloadBytes(mtu)

            val chunkCount = ((payload.size + payloadSize - 1) / payloadSize).coerceAtLeast(1)
            if (chunkCount > GattPacketCodec.MAX_CHUNKS) {
                session.fail(GattTransportError.FRAME_TOO_LARGE)
                return GattSendResult.Failed(
                    GattTransportError.FRAME_TOO_LARGE,
                    session.snapshot()
                )
            }

            session.expectedTransferId = transferId
            session.frameCount = chunkCount
            session.mtu = mtu
            session.bytesSent = 0

            for (index in 0 until chunkCount) {
                session.requireRuntime()
                val start = index * payloadSize
                val end = minOf(payload.size, start + payloadSize)
                val frame = GattPacketCodec.encodeData(
                    GattPacketCodec.DataFrame(
                        transferId = transferId,
                        chunkIndex = index,
                        chunkCount = chunkCount,
                        payload = payload.copyOfRange(start, end)
                    )
                )

                session.transition(
                    state = GattTransportState.WRITING,
                    frameIndex = index
                )
                writeCharacteristic(gatt, session, frame)
                session.bytesSent += end - start
            }

            session.requireRuntime()
            session.transition(GattTransportState.WAITING_ACK, frameIndex = null)
            val accepted = try {
                withTimeout(BleGattConstants.ACK_TIMEOUT_MS) {
                    select<Boolean> {
                        session.ack.onAwait { it.accepted }
                        session.failure.onAwait { throw GattTransportException(session.error) }
                    }
                }
            } catch (e: GattTransportException) {
                return GattSendResult.Failed(e.error, session.snapshot())
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                session.fail(GattTransportError.ACK_TIMEOUT)
                return GattSendResult.Failed(GattTransportError.ACK_TIMEOUT, session.snapshot())
            }

            return if (accepted) {
                session.transition(GattTransportState.COMPLETED)
                GattSendResult.Delivered(
                    negotiatedMtu = mtu,
                    bytesSent = session.bytesSent,
                    framesSent = chunkCount,
                    snapshot = session.snapshot()
                )
            } else {
                session.fail(GattTransportError.ACK_REJECTED)
                GattSendResult.Failed(GattTransportError.ACK_REJECTED, session.snapshot())
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (e: GattTransportException) {
            session.fail(e.error)
            return GattSendResult.Failed(e.error, session.snapshot())
        } catch (_: Exception) {
            session.fail(GattTransportError.INTERNAL_ERROR)
            return GattSendResult.Failed(GattTransportError.INTERNAL_ERROR, session.snapshot())
        } finally {
            session.markDisconnecting()
            closeGatt(gatt)
        }
    }

    /** Streams one attachment over a single GATT session, with resumable per-block custody ACKs. */
    @SuppressLint("MissingPermission")
    suspend fun sendAttachment(
        device: BluetoothDevice,
        manifest: AttachmentTransferManifest,
        file: File,
        runtimeGeneration: Long,
        isRuntimeGenerationCurrent: () -> Boolean,
        onProgress: suspend (Long) -> Unit = {},
        onState: (GattTransportSnapshot) -> Unit = {}
    ): BleAttachmentSendResult {
        val session = ClientSession(
            address = safeAddress(device),
            messageId = manifest.messageId,
            runtimeGeneration = runtimeGeneration,
            isRuntimeGenerationCurrent = isRuntimeGenerationCurrent,
            publish = onState
        )
        var transferStage = "VALIDATE"
        var acknowledgedBytes = 0L
        var estimatedGattWrites: Long? = null
        fun failed(code: String): BleAttachmentSendResult = BleAttachmentSendResult.failed(
            code = code,
            stage = transferStage,
            messageId = manifest.messageId,
            acknowledgedBytes = acknowledgedBytes,
            estimatedGattWrites = estimatedGattWrites,
            snapshot = session.snapshot()
        )

        if (!manifest.isValid()) return failed("INVALID_MANIFEST")
        if (manifest.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES) return failed("BLE_SIZE_LIMIT")
        if (!file.isFile || file.length() != manifest.sizeBytes ||
            runCatching { AttachmentFileStore.sha256(file) }.getOrNull() != manifest.sha256) {
            return failed("LOCAL_FILE_MISSING_OR_CHANGED")
        }

        transferStage = "CONNECT"
        val gatt = try { connect(device, session) } catch (_: CancellationException) { throw CancellationException() }
            ?: return failed(session.error.takeIf { it != GattTransportError.NONE }?.name ?: "CONNECTION_FAILED")

        try {
            session.requireRuntime()
            transferStage = "SERVICE_DISCOVERY"
            session.transition(GattTransportState.DISCOVERING_SERVICES)
            discoverServices(gatt, session)
            transferStage = "NOTIFICATIONS"
            session.transition(GattTransportState.SETTING_UP_NOTIFICATIONS)
            enableNotifications(gatt, session)
            transferStage = "MTU_NEGOTIATION"
            session.transition(GattTransportState.NEGOTIATING_MTU)
            // Bulk media benefits from a shorter connection interval. Stacks that
            // reject this request continue with their existing connection policy.
            runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            val mtu = negotiateMtu(gatt, session, BleGattConstants.TARGET_ATTACHMENT_MTU)
            val payloadBytes = GattPacketCodec.safePayloadBytes(mtu)
            session.transition(GattTransportState.READY)
            val streamId = UUID.randomUUID().leastSignificantBits
            var frameFailureCode: String? = null

            suspend fun sendFrame(bytes: ByteArray, expectedOffset: Long? = null): GattPacketCodec.AckFrame? {
                val frameTransferId = UUID.randomUUID().leastSignificantBits
                val count = ((bytes.size + payloadBytes - 1) / payloadBytes).coerceAtLeast(1)
                if (count > GattPacketCodec.MAX_CHUNKS) {
                    frameFailureCode = "FRAME_TOO_LARGE"
                    session.fail(GattTransportError.FRAME_TOO_LARGE)
                    return null
                }
                session.expectedTransferId = frameTransferId
                session.ack = CompletableDeferred()
                session.frameCount = count
                session.bytesSent = 0
                for (index in 0 until count) {
                    session.requireRuntime()
                    val start = index * payloadBytes
                    val end = minOf(bytes.size, start + payloadBytes)
                    val frame = GattPacketCodec.encodeData(
                        GattPacketCodec.DataFrame(frameTransferId, index, count, bytes.copyOfRange(start, end))
                    )
                    session.transition(GattTransportState.WRITING, index)
                    writeCharacteristic(gatt, session, frame)
                    session.bytesSent += end - start
                }
                session.transition(GattTransportState.WAITING_ACK, null)
                val ack = try {
                    withTimeout(BleGattConstants.ACK_TIMEOUT_MS) {
                        select<GattPacketCodec.AckFrame> {
                            session.ack.onAwait { it }
                            session.failure.onAwait { throw GattTransportException(session.error) }
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    frameFailureCode = "ACK_TIMEOUT"
                    session.fail(GattTransportError.ACK_TIMEOUT)
                    return null
                }
                if (!ack.accepted) {
                    frameFailureCode = "ACK_REJECTED"
                    session.fail(GattTransportError.ACK_REJECTED)
                    return null
                }
                if (expectedOffset != null && ack.offset != expectedOffset) {
                    frameFailureCode = "PROTOCOL_ERROR"
                    session.fail(GattTransportError.PROTOCOL_ERROR)
                    return null
                }
                return ack
            }

            transferStage = "BEGIN"
            val beginAck = sendFrame(BleAttachmentFrameCodec.encodeBegin(streamId, manifest))
                ?: return failed(frameFailureCode ?: "ACK_MISSING")
            val resumeOffset = beginAck.offset ?: run {
                session.fail(GattTransportError.PROTOCOL_ERROR)
                return failed("PROTOCOL_ERROR")
            }
            if (resumeOffset !in 0..manifest.sizeBytes) {
                session.fail(GattTransportError.PROTOCOL_ERROR)
                return failed("PROTOCOL_ERROR")
            }
            var offset = resumeOffset
            acknowledgedBytes = offset
            onProgress(offset)
            val remainingBytes = manifest.sizeBytes - resumeOffset
            // BEGIN is already acknowledged, so count each resumed DATA frame
            // and FINISH exactly. Each application frame is packetized separately
            // and rounded up to whole GATT writes.
            val chunkHeaderBytes = BleAttachmentFrameCodec.encodeChunk(
                streamId, manifest.messageId, 0L, byteArrayOf(0)
            ).size - 1
            val finishFrameBytes = BleAttachmentFrameCodec.encodeFinish(streamId, manifest.messageId).size
            fun writeCount(frameBytes: Long): Long =
                (frameBytes + payloadBytes - 1L) / payloadBytes
            var bytesLeftToEstimate = remainingBytes
            var writeEstimate = writeCount(finishFrameBytes.toLong())
            while (bytesLeftToEstimate > 0L) {
                val chunkBytes = minOf(BleAttachmentFrameCodec.MAX_CHUNK_BYTES.toLong(), bytesLeftToEstimate)
                writeEstimate += writeCount(chunkBytes + chunkHeaderBytes)
                bytesLeftToEstimate -= chunkBytes
            }
            estimatedGattWrites = writeEstimate
            if (estimatedGattWrites!! > BleGattConstants.MAX_ATTACHMENT_GATT_WRITES) {
                transferStage = "LINK_CAPACITY_CHECK"
                session.fail(GattTransportError.ATTACHMENT_LINK_TOO_SLOW)
                return failed("ATTACHMENT_LINK_TOO_SLOW")
            }

            file.inputStream().buffered().use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val count = input.skip(offset - skipped)
                    if (count <= 0L) {
                        if (input.read() < 0) return failed("FILE_SEEK_FAILED")
                        skipped++
                    } else skipped += count
                }
                val buffer = ByteArray(BleAttachmentFrameCodec.MAX_CHUNK_BYTES)
                while (offset < manifest.sizeBytes) {
                    session.requireRuntime()
                    val wanted = minOf(buffer.size.toLong(), manifest.sizeBytes - offset).toInt()
                    var read = 0
                    while (read < wanted) {
                        val count = input.read(buffer, read, wanted - read)
                        if (count < 0) return failed("FILE_READ_FAILED")
                        if (count > 0) read += count
                    }
                    val nextOffset = offset + read
                    val appFrame = BleAttachmentFrameCodec.encodeChunk(
                        streamId, manifest.messageId, offset, buffer.copyOf(read)
                    )
                    transferStage = "DATA"
                    if (sendFrame(appFrame, nextOffset) == null) {
                        return failed(frameFailureCode ?: "ACK_MISSING")
                    }
                    offset = nextOffset
                    acknowledgedBytes = offset
                    onProgress(offset)
                }
            }

            transferStage = "FINISH"
            val finish = sendFrame(BleAttachmentFrameCodec.encodeFinish(streamId, manifest.messageId), manifest.sizeBytes)
                ?: return failed(frameFailureCode ?: "ACK_MISSING")
            session.transition(GattTransportState.COMPLETED)
            return BleAttachmentSendResult(
                succeeded = finish.accepted,
                failureCode = if (finish.accepted) null else "ACK_REJECTED",
                stage = "COMPLETE",
                acknowledgedBytes = acknowledgedBytes,
                snapshot = session.snapshot()
            )
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (e: GattTransportException) {
            session.fail(e.error)
            return failed(e.error.name)
        } catch (_: Exception) {
            session.fail(GattTransportError.INTERNAL_ERROR)
            return failed(session.error.takeIf { it != GattTransportError.NONE }?.name ?: "INTERNAL_ERROR")
        } finally {
            session.markDisconnecting()
            closeGatt(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readIdentity(
        device: BluetoothDevice,
        runtimeGeneration: Long,
        isRuntimeGenerationCurrent: () -> Boolean,
        onState: (GattTransportSnapshot) -> Unit = {}
    ): RemoteIdentity? {
        val session = ClientSession(
            address = safeAddress(device),
            runtimeGeneration = runtimeGeneration,
            isRuntimeGenerationCurrent = isRuntimeGenerationCurrent,
            publish = onState
        )

        val gatt = connect(device, session) ?: return null
        try {
            session.requireRuntime()
            session.transition(GattTransportState.DISCOVERING_SERVICES)
            discoverServices(gatt, session)
            session.requireRuntime()
            session.transition(GattTransportState.READY)

            val characteristic = session.service?.getCharacteristic(
                BleGattConstants.IDENTITY_CHARACTERISTIC_UUID
            ) ?: throw GattTransportException(GattTransportError.CHARACTERISTIC_MISSING)

            val waiter = CompletableDeferred<ByteArray>()
            session.readValue = waiter

            val started = runCatching {
                gatt.readCharacteristic(characteristic)
            }.getOrDefault(false)

            if (!started) {
                session.readValue = null
                throw GattTransportException(GattTransportError.IDENTITY_READ_FAILED)
            }

            val bytes = try {
                withTimeout(BleGattConstants.IDENTITY_READ_TIMEOUT_MS) {
                    waiter.await()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw GattTransportException(GattTransportError.IDENTITY_READ_FAILED)
            }

            return decodeIdentity(bytes)
                ?: throw GattTransportException(GattTransportError.IDENTITY_DECODE_FAILED)
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (e: GattTransportException) {
            session.fail(e.error)
            return null
        } catch (_: Exception) {
            session.fail(GattTransportError.INTERNAL_ERROR)
            return null
        } finally {
            session.readValue?.cancel()
            session.readValue = null
            closeGatt(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(
        device: BluetoothDevice,
        session: ClientSession
    ): BluetoothGatt? {
        session.transition(GattTransportState.CONNECTING)
        return try {
            withTimeout(BleGattConstants.CONNECT_TIMEOUT_MS) {
                connectInternal(device, session)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            session.fail(GattTransportError.CONNECTION_FAILED)
            session.gatt?.let(::closeGatt)
            null
        } catch (_: SecurityException) {
            session.fail(GattTransportError.PERMISSION_DENIED)
            session.gatt?.let(::closeGatt)
            null
        } catch (_: Exception) {
            session.fail(GattTransportError.CONNECTION_FAILED)
            session.gatt?.let(::closeGatt)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(
        device: BluetoothDevice,
        session: ClientSession
    ): BluetoothGatt? = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (!session.isRuntimeCurrent()) {
                    session.fail(com.hybridmesh.relay.messaging.model.GattTransportError.CONNECTION_FAILED)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    if (!resumed.getAndSet(true)) continuation.resume(null)
                    return
                }
                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    session.gatt = gatt
                    session.transition(GattTransportState.CONNECTED)
                    if (!resumed.getAndSet(true)) continuation.resume(gatt)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    session.fail(if (status == BluetoothGatt.GATT_SUCCESS) GattTransportError.DISCONNECTED else GattTransportError.CONNECTION_FAILED)
                    if (!resumed.getAndSet(true)) continuation.resume(null)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!session.isRuntimeCurrent()) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    session.fail(GattTransportError.SERVICE_DISCOVERY_FAILED)
                    return
                }

                val service = gatt.getService(BleGattConstants.SERVICE_UUID)
                val rx = service?.getCharacteristic(BleGattConstants.RX_CHARACTERISTIC_UUID)
                val tx = service?.getCharacteristic(BleGattConstants.TX_CHARACTERISTIC_UUID)
                if (service == null || rx == null || tx == null) {
                    session.fail(GattTransportError.CHARACTERISTIC_MISSING)
                    return
                }

                session.service = service
                session.rx = rx
                session.tx = tx
                session.servicesDiscovered.complete(Unit)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (!session.isRuntimeCurrent()) return
                if (descriptor.uuid != BleGattConstants.CLIENT_CONFIG_UUID) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    session.notificationsReady.complete(Unit)
                } else {
                    session.fail(GattTransportError.NOTIFICATION_SETUP_FAILED)
                }
            }

            @Deprecated("Deprecated in Android API")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (!session.isRuntimeCurrent()) return
                handleNotification(session, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (!session.isRuntimeCurrent()) return
                handleNotification(session, value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (!session.isRuntimeCurrent()) return
                val waiter = session.writeWaiter
                session.writeWaiter = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    waiter?.complete(Unit)
                } else {
                    waiter?.completeExceptionally(
                        GattTransportException(GattTransportError.WRITE_FAILED)
                    )
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (!session.isRuntimeCurrent()) return
                val waiter = session.readValue
                session.readValue = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    waiter?.complete(characteristic.value)
                } else {
                    waiter?.completeExceptionally(
                        GattTransportException(GattTransportError.IDENTITY_READ_FAILED)
                    )
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (!session.isRuntimeCurrent()) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    session.mtu = mtu.coerceAtLeast(GattPacketCodec.MIN_ATT_MTU)
                }
                session.mtuChanged.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        continuation.invokeOnCancellation {
            resumed.set(true)
            session.fail(GattTransportError.CONNECTION_FAILED)
            runCatching { session.gatt?.disconnect() }
            runCatching { session.gatt?.close() }
        }

        try {
            val opened = device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            )
            session.gatt = opened
        } catch (_: SecurityException) {
            session.fail(GattTransportError.PERMISSION_DENIED)
            if (!resumed.getAndSet(true)) continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            session.fail(GattTransportError.CONNECTION_FAILED)
            if (!resumed.getAndSet(true)) continuation.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverServices(gatt: BluetoothGatt, session: ClientSession) {
        session.requireRuntime()
        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
        if (!started) throw GattTransportException(GattTransportError.SERVICE_DISCOVERY_FAILED)

        try {
            withTimeout(BleGattConstants.SERVICE_DISCOVERY_TIMEOUT_MS) {
                select<Unit> {
                    session.servicesDiscovered.onAwait { }
                    session.failure.onAwait { throw GattTransportException(session.error) }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GattTransportException(GattTransportError.SERVICE_DISCOVERY_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(gatt: BluetoothGatt, session: ClientSession) {
        session.requireRuntime()
        val tx = session.tx ?: throw GattTransportException(GattTransportError.CHARACTERISTIC_MISSING)
        if (!gatt.setCharacteristicNotification(tx, true)) {
            throw GattTransportException(GattTransportError.NOTIFICATION_SETUP_FAILED)
        }

        val descriptor = tx.getDescriptor(BleGattConstants.CLIENT_CONFIG_UUID)
            ?: throw GattTransportException(GattTransportError.NOTIFICATION_SETUP_FAILED)

        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!accepted) throw GattTransportException(GattTransportError.NOTIFICATION_SETUP_FAILED)

        try {
            withTimeout(BleGattConstants.NOTIFICATION_SETUP_TIMEOUT_MS) {
                select<Unit> {
                    session.notificationsReady.onAwait { }
                    session.failure.onAwait { throw GattTransportException(session.error) }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GattTransportException(GattTransportError.NOTIFICATION_SETUP_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun negotiateMtu(
        gatt: BluetoothGatt,
        session: ClientSession,
        targetMtu: Int = BleGattConstants.TARGET_MTU
    ): Int {
        session.requireRuntime()
        session.mtu = GattPacketCodec.MIN_ATT_MTU
        val accepted = runCatching {
            gatt.requestMtu(targetMtu)
        }.getOrDefault(false)

        if (!accepted) {
            return session.mtu
        }

        runCatching {
            withTimeout(BleGattConstants.MTU_TIMEOUT_MS) {
                session.mtuChanged.await()
            }
        }
        return session.mtu.coerceAtLeast(GattPacketCodec.MIN_ATT_MTU)
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        session: ClientSession,
        value: ByteArray
    ) {
        session.requireRuntime()
        val characteristic = session.rx
            ?: throw GattTransportException(GattTransportError.CHARACTERISTIC_MISSING)

        if (value.size > GattPacketCodec.maxCharacteristicValueBytes(session.mtu)) {
            throw GattTransportException(GattTransportError.FRAME_TOO_LARGE)
        }

        val waiter = CompletableDeferred<Unit>()
        session.writeWaiter = waiter

        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!accepted) {
            session.writeWaiter = null
            throw GattTransportException(GattTransportError.WRITE_REJECTED)
        }

        try {
            withTimeout(BleGattConstants.WRITE_TIMEOUT_MS) {
                waiter.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            session.writeWaiter = null
            throw GattTransportException(GattTransportError.WRITE_FAILED)
        } catch (e: GattTransportException) {
            throw e
        }
    }

    private fun handleNotification(session: ClientSession, value: ByteArray) {
        val ack = GattPacketCodec.decode(value) as? GattPacketCodec.AckFrame ?: return
        if (ack.transferId != session.expectedTransferId) return
        session.ack.complete(ack)
    }

    private fun decodeIdentity(bytes: ByteArray): RemoteIdentity? {
        if (bytes.size < 3) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.get() != BleGattConstants.IDENTITY_VERSION) return null
            val nameLength = buffer.get().toInt() and 0xFF
            if (nameLength > BleGattConstants.IDENTITY_NAME_MAX_BYTES || buffer.remaining() < nameLength + 1) return null
            val name = ByteArray(nameLength)
            buffer.get(name)
            val deviceType = if (buffer.get().toInt() == 2) "RELAY" else "PHONE"
            RemoteIdentity(
                displayName = name.toString(Charsets.UTF_8).ifBlank { "Hybrid Mesh Device" },
                deviceType = deviceType
            )
        }.getOrNull()
    }

    private fun messageTypeCode(type: String): Byte = when (type) {
        "PRIORITY" -> 2
        "EMERGENCY" -> 3
        "LOCATION" -> 4
        "ATTACHMENT" -> 5
        else -> 1
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice): String =
        runCatching { device.address }.getOrDefault(device.hashCode().toString())

    data class RemoteIdentity(
        val displayName: String,
        val deviceType: String
    )

    sealed class GattSendResult {
        data class Delivered(
            val negotiatedMtu: Int,
            val bytesSent: Int,
            val framesSent: Int,
            val snapshot: GattTransportSnapshot
        ) : GattSendResult()

        data class Failed(
            val error: GattTransportError,
            val snapshot: GattTransportSnapshot
        ) : GattSendResult()
    }

    private class GattTransportException(
        val error: GattTransportError
    ) : Exception(error.name)

    private class ClientSession(
        val address: String,
        val messageId: String? = null,
        val runtimeGeneration: Long = 0L,
        private val isRuntimeGenerationCurrent: () -> Boolean = { true },
        private val publish: (GattTransportSnapshot) -> Unit
    ) {
        var gatt: BluetoothGatt? = null
        var mtu = GattPacketCodec.MIN_ATT_MTU
        var service: BluetoothGattService? = null
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null
        var writeWaiter: CompletableDeferred<Unit>? = null
        var readValue: CompletableDeferred<ByteArray>? = null
        var expectedTransferId: Long = Long.MIN_VALUE
        var frameIndex: Int? = null
        var frameCount: Int? = null
        var bytesSent: Int = 0
        var error: GattTransportError = GattTransportError.NONE
        var currentState: GattTransportState = GattTransportState.IDLE

        val servicesDiscovered = CompletableDeferred<Unit>()
        val notificationsReady = CompletableDeferred<Unit>()
        val mtuChanged = CompletableDeferred<Boolean>()
        var ack = CompletableDeferred<GattPacketCodec.AckFrame>()
        val failure = CompletableDeferred<Unit>()

        fun isRuntimeCurrent(): Boolean = isRuntimeGenerationCurrent()

        fun requireRuntime() {
            if (!isRuntimeCurrent()) {
                fail(com.hybridmesh.relay.messaging.model.GattTransportError.CONNECTION_FAILED)
                throw GattTransportException(com.hybridmesh.relay.messaging.model.GattTransportError.CONNECTION_FAILED)
            }
        }

        fun transition(
            state: GattTransportState,
            frameIndex: Int? = this.frameIndex
        ) {
            this.frameIndex = frameIndex
            this.currentState = state
            publish(snapshot(state))
        }

        fun markDisconnecting() {
            currentState = GattTransportState.DISCONNECTING
            publish(snapshot(GattTransportState.DISCONNECTING))
        }

        fun fail(newError: GattTransportError) {
            if (error == GattTransportError.NONE) error = newError
            if (!failure.isCompleted) failure.complete(Unit)
            publish(snapshot(GattTransportState.FAILED))
        }

        fun snapshot(state: GattTransportState = currentState): GattTransportSnapshot =
            GattTransportSnapshot(
                peerAddress = address,
                messageId = messageId,
                state = state,
                error = error,
                mtu = mtu,
                frameIndex = frameIndex,
                frameCount = frameCount,
                bytesSent = bytesSent,
                updatedAt = System.currentTimeMillis()
            )
    }
}
