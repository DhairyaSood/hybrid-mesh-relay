package com.hybridmesh.relay.messaging.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.hybridmesh.relay.messaging.ble.BleAttachmentSendResult
import com.hybridmesh.relay.messaging.ble.BleGattClient
import com.hybridmesh.relay.messaging.attachment.AttachmentTransferManifest
import com.hybridmesh.relay.messaging.model.GattTransportSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.MeshTransportPreferences
import com.hybridmesh.relay.network.NetworkManager
import java.io.File

/** BLE/GATT adapter preserving the existing Neyra frame and acceptance protocol. */
internal class BleGattMeshTransport(
    context: Context,
    private val networkManager: NetworkManager,
    private val gattClient: BleGattClient,
    private val onState: (GattTransportSnapshot) -> Unit
) : MeshTransport {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val transportPreferences = MeshTransportPreferences.getInstance(appContext)
    private val peerLocks = PeerLockRegistry()

    override val kind: MeshTransportKind = MeshTransportKind.BLE_GATT

    override fun isAvailable(): Boolean {
        return availabilityFailure() == null
    }

    fun availabilityFailure(): String? {
        val state = networkManager.state.value
        val selection = transportPreferences.selection.value
        return when {
            !selection.bleEnabled -> "BLE_DISABLED"
            !state.bleSupported -> "BLE_NOT_SUPPORTED"
            !state.permissionsGranted -> "BLE_PERMISSION_MISSING"
            state.bluetoothState != BluetoothState.ON -> "BLUETOOTH_OFF"
            !state.gattServerReady -> "BLE_GATT_SERVER_NOT_READY"
            else -> null
        }
    }

    override fun canReach(peer: MeshTransportPeer): Boolean =
        !peer.bleAddress.isNullOrBlank() && !peer.bleAddress.equals("Unknown", true)

    @SuppressLint("MissingPermission")
    override suspend fun send(
        peer: MeshTransportPeer,
        encodedPacket: ByteArray,
        messageId: String?
    ): MeshTransportSendResult {
        if (!isAvailable()) {
            return MeshTransportSendResult.Unavailable(kind, "BLE_RUNTIME_NOT_READY")
        }
        if (!canReach(peer)) {
            return MeshTransportSendResult.Unavailable(kind, "BLE_ENDPOINT_UNAVAILABLE")
        }

        val address = peer.bleAddress
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
            ?: return MeshTransportSendResult.Unavailable(kind, "BLE_ENDPOINT_UNAVAILABLE")
        val device = runCatching { bluetoothManager?.adapter?.getRemoteDevice(address) }
            .getOrNull()
            ?: return MeshTransportSendResult.Unavailable(kind, "BLE_DEVICE_NOT_FOUND")
        val lease = peerLocks.tryAcquire(peer.nodeId)
            ?: return MeshTransportSendResult.Unavailable(kind, "BLE_PEER_BUSY")

        try {
            val generation = networkManager.runtimeGeneration.value
            if (!isAvailable()) {
                return MeshTransportSendResult.Unavailable(kind, "BLE_RUNTIME_NOT_READY")
            }

            val result = gattClient.sendPayload(
                device = device,
                payload = encodedPacket,
                messageId = messageId,
                transferId = MeshPacketCodec.newTransferId(),
                runtimeGeneration = generation,
                isRuntimeGenerationCurrent = {
                    networkManager.runtimeGeneration.value == generation && isAvailable()
                },
                onState = onState
            )
            return if (result is BleGattClient.GattSendResult.Delivered) {
                MeshTransportSendResult.Accepted(kind)
            } else {
                val reason = (result as? BleGattClient.GattSendResult.Failed)
                    ?.error?.name ?: "BLE_SEND_FAILED"
                MeshTransportSendResult.Failed(kind, reason)
            }
        } finally {
            lease.close()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendAttachment(
        peer: MeshTransportPeer,
        manifest: AttachmentTransferManifest,
        file: File,
        onProgress: suspend (Long) -> Unit
    ): BleAttachmentSendResult {
        fun failed(code: String, stage: String = "ROUTE") = BleAttachmentSendResult.failed(
            code = code,
            stage = stage,
            messageId = manifest.messageId
        )

        availabilityFailure()?.let { return failed(it) }
        if (manifest.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES) return failed("BLE_SIZE_LIMIT", "VALIDATE")
        if (!canReach(peer)) return failed("BLE_ENDPOINT_UNAVAILABLE")
        val address = peer.bleAddress?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
            ?: return failed("BLE_ENDPOINT_UNAVAILABLE")
        val device = runCatching { bluetoothManager?.adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return failed("BLE_DEVICE_NOT_FOUND")
        val lease = peerLocks.tryAcquire(peer.nodeId) ?: return failed("BLE_PEER_BUSY")
        try {
            val generation = networkManager.runtimeGeneration.value
            availabilityFailure()?.let { return failed(it) }
            return gattClient.sendAttachment(
                device = device,
                manifest = manifest,
                file = file,
                runtimeGeneration = generation,
                isRuntimeGenerationCurrent = {
                    networkManager.runtimeGeneration.value == generation && isAvailable()
                },
                onProgress = { onProgress(it) },
                onState = onState
            )
        } finally {
            lease.close()
        }
    }
}
