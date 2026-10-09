package com.hybridmesh.relay.messaging.mesh

/** Physical bearer carrying the unchanged Neyra MeshPacketCodec payload. */
enum class MeshTransportKind(val persistedName: String, val traceCode: Int) {
    BLE_GATT("BLE_MESH", 1),
    WIFI_DIRECT("WIFI_DIRECT", 2),
    /** Reserved stable code for the future LoRa transport; not implemented yet. */
    LORA("LORA", 3);

    companion object {
        fun fromTraceCode(code: Int): MeshTransportKind? = entries.firstOrNull { it.traceCode == code }
    }
}

/** Stable Neyra identity plus optional, transport-specific runtime endpoints. */
data class MeshTransportPeer(
    val nodeId: String,
    val bleAddress: String? = null,
    val wifiAddress: String? = null
)

sealed interface MeshTransportSendResult {
    data class Accepted(val transport: MeshTransportKind) : MeshTransportSendResult
    data class Unavailable(val transport: MeshTransportKind, val reason: String) : MeshTransportSendResult
    data class Failed(val transport: MeshTransportKind, val reason: String) : MeshTransportSendResult
}

/**
 * Transport boundary for one next-hop send. Implementations must carry the
 * already-encoded mesh packet and must not create application messages of their own.
 * A successful result means the receiving mesh layer accepted the complete packet,
 * not merely that the local radio/socket accepted bytes for transmission.
 */
interface MeshTransport {
    val kind: MeshTransportKind
    fun isAvailable(): Boolean
    fun canReach(peer: MeshTransportPeer): Boolean
    suspend fun send(
        peer: MeshTransportPeer,
        encodedPacket: ByteArray,
        messageId: String?
    ): MeshTransportSendResult
}
