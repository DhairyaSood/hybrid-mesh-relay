package com.hybridmesh.relay.messaging.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
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

    override val kind: MeshTransportKind = MeshTransportKind.BLE_GATT

    override fun isAvailable(): Boolean {
        val state = networkManager.state.value
        return transportPreferences.selection.value.bleEnabled &&
            state.bleSupported && state.permissionsGranted &&
            state.bluetoothState == BluetoothState.ON && state.gattServerReady
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
        val lease = BleGattPeerLocks.tryAcquire(peer.nodeId)
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
    ): Boolean {
        if (!isAvailable() || manifest.sizeBytes > AttachmentTransferManifest.MAX_BLE_BYTES || !canReach(peer)) return false
        val address = peer.bleAddress?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) } ?: return false
        val device = runCatching { bluetoothManager?.adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        val lease = BleGattPeerLocks.tryAcquire(peer.nodeId) ?: return false
        try {
            val generation = networkManager.runtimeGeneration.value
            if (!isAvailable()) return false
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
