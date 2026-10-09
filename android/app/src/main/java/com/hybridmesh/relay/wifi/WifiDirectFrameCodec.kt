package com.hybridmesh.relay.wifi

import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Bounded, versioned TCP framing for existing encoded Neyra mesh packets. */
internal object WifiDirectFrameCodec {
    private const val HELLO_MAGIC = 0x4E595231 // NYR1
    private const val FRAME_DATA: Byte = 1
    private const val FRAME_ACK: Byte = 2
    private const val FRAME_HEADER_BYTES = 1 + java.lang.Long.BYTES
    private const val MIN_FRAME_BYTES = FRAME_HEADER_BYTES + 1
    private const val MAX_FRAME_BYTES = MeshPacketCodec.MAX_PACKET_BYTES + FRAME_HEADER_BYTES

    data class Hello(
        val nodeId: String,
        val deviceName: String,
        val protocolVersion: Int,
        val canRelay: Boolean,
        val canStoreForward: Boolean
    )

    sealed interface Frame {
        val transferId: Long
        data class Data(override val transferId: Long, val payload: ByteArray) : Frame
        data class Ack(override val transferId: Long, val accepted: Boolean) : Frame
    }

    fun writeHello(output: DataOutputStream, hello: Hello) {
        output.writeInt(HELLO_MAGIC)
        output.writeUTF(hello.nodeId)
        output.writeUTF(hello.deviceName)
        output.writeInt(hello.protocolVersion)
        output.writeBoolean(hello.canRelay)
        output.writeBoolean(hello.canStoreForward)
        output.flush()
    }

    fun readHello(input: DataInputStream): Hello {
        if (input.readInt() != HELLO_MAGIC) throw IOException("Invalid Neyra hello")
        return Hello(
            nodeId = input.readUTF(),
            deviceName = input.readUTF(),
            protocolVersion = input.readInt(),
            canRelay = input.readBoolean(),
            canStoreForward = input.readBoolean()
        )
    }

    fun writeData(output: DataOutputStream, transferId: Long, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MeshPacketCodec.MAX_PACKET_BYTES) { "Invalid mesh payload size" }
        writeFrame(output, FRAME_DATA, transferId, payload)
    }

    fun writeAck(output: DataOutputStream, transferId: Long, accepted: Boolean) {
        writeFrame(output, FRAME_ACK, transferId, byteArrayOf(if (accepted) 1 else 0))
    }

    fun readFrame(input: DataInputStream): Frame {
        val length = input.readInt()
        if (length !in MIN_FRAME_BYTES..MAX_FRAME_BYTES) throw IOException("Invalid frame length")
        val type = input.readByte()
        val transferId = input.readLong()
        val payloadLength = length - FRAME_HEADER_BYTES
        val payload = ByteArray(payloadLength)
        input.readFully(payload)
        return when (type) {
            FRAME_DATA -> {
                if (payload.isEmpty() || payload.size > MeshPacketCodec.MAX_PACKET_BYTES) {
                    throw IOException("Invalid packet size")
                }
                Frame.Data(transferId, payload)
            }
            FRAME_ACK -> {
                if (payload.size != 1 || (payload[0].toInt() != 0 && payload[0].toInt() != 1)) {
                    throw IOException("Invalid transport ACK")
                }
                Frame.Ack(transferId, payload[0].toInt() == 1)
            }
            else -> throw IOException("Unknown frame type")
        }
    }

    private fun writeFrame(output: DataOutputStream, type: Byte, transferId: Long, payload: ByteArray) {
        val bodyLength = FRAME_HEADER_BYTES + payload.size
        if (bodyLength !in MIN_FRAME_BYTES..MAX_FRAME_BYTES) throw IOException("Frame too large")
        output.writeInt(bodyLength)
        output.writeByte(type.toInt())
        output.writeLong(transferId)
        output.write(payload)
        output.flush()
    }
}
