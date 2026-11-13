package com.hybridmesh.relay.messaging.lora

import com.hybridmesh.relay.messaging.lora.BleBridgeConstants.ATT_HEADER_BYTES
import com.hybridmesh.relay.messaging.lora.BleBridgeConstants.CHUNK_HEADER_BYTES
import com.hybridmesh.relay.messaging.lora.BleBridgeConstants.MAX_PACKET_BYTES
import com.hybridmesh.relay.messaging.lora.BleBridgeConstants.MIN_ATT_MTU

/**
 * Plain chunk framing for the NEYRA-LORA bridge (protocol/packet-format.md):
 * every write/notification is `[chunkIndex: 1][chunkCount: 1][data]`, chunks 0..count-1 in order.
 * Pure Kotlin so it is covered by JVM unit tests.
 */
object BleBridgeFraming {
    /** Packet bytes that fit in one chunk at [mtu]. */
    fun chunkDataBytes(mtu: Int): Int = mtu.coerceAtLeast(MIN_ATT_MTU) - ATT_HEADER_BYTES - CHUNK_HEADER_BYTES

    /** Splits one whole mesh packet into chunks. Throws for empty or over-limit packets. */
    fun split(packet: ByteArray, mtu: Int): List<ByteArray> {
        require(packet.isNotEmpty()) { "empty packet" }
        require(packet.size <= MAX_PACKET_BYTES) { "packet ${packet.size} bytes exceeds $MAX_PACKET_BYTES" }
        val dataPerChunk = chunkDataBytes(mtu)
        val count = (packet.size + dataPerChunk - 1) / dataPerChunk  // <= 15 even at MTU 23
        return (0 until count).map { index ->
            val start = index * dataPerChunk
            val end = minOf(start + dataPerChunk, packet.size)
            ByteArray(CHUNK_HEADER_BYTES + end - start).also { chunk ->
                chunk[0] = index.toByte()
                chunk[1] = count.toByte()
                packet.copyInto(chunk, CHUNK_HEADER_BYTES, start, end)
            }
        }
    }
}

/**
 * Reassembles bridge notifications into whole packets with the same rules the
 * firmware applies to phone writes. Not thread-safe: feed it from one thread.
 */
class BleBridgeReassembler(
    private val timeoutMs: Long = BleBridgeConstants.REASSEMBLY_TIMEOUT_MS,
    private val maxPacketBytes: Int = MAX_PACKET_BYTES
) {
    /** [packet] is a complete packet; [dropReason] names anything discarded on the way. Both may be set. */
    class Outcome(val packet: ByteArray? = null, val dropReason: String? = null)

    private val buffer = java.io.ByteArrayOutputStream()
    private var chunkCount = 0
    private var nextIndex = 0
    private var lastChunkAt = 0L
    private var tooLarge = false

    val inProgress: Boolean get() = chunkCount != 0

    fun accept(chunk: ByteArray, nowMs: Long): Outcome {
        if (chunk.size < CHUNK_HEADER_BYTES + 1) {
            return Outcome(dropReason = "CHUNK_TOO_SHORT bytes=${chunk.size}")
        }
        val index = chunk[0].toInt() and 0xFF
        val count = chunk[1].toInt() and 0xFF
        if (count == 0 || index >= count) {
            reset()
            return Outcome(dropReason = "BAD_CHUNK_HEADER index=$index count=$count")
        }

        var dropped: String? = null
        if (inProgress && nowMs - lastChunkAt > timeoutMs) {
            dropped = "REASSEMBLY_TIMEOUT got=$nextIndex/$chunkCount"
            reset()
        }
        if (index == 0) {
            if (inProgress) dropped = "TRANSFER_RESTARTED got=$nextIndex/$chunkCount"
            reset()
            chunkCount = count
        } else if (!inProgress) {
            val missing = "MISSING_FIRST_CHUNK index=$index count=$count"
            return Outcome(dropReason = if (dropped != null) "$dropped; $missing" else missing)
        } else if (count != chunkCount || index != nextIndex) {
            val reason = "OUT_OF_ORDER expected=$nextIndex/$chunkCount got=$index/$count"
            reset()
            return Outcome(dropReason = reason)
        }

        lastChunkAt = nowMs
        nextIndex = index + 1
        val dataLength = chunk.size - CHUNK_HEADER_BYTES
        if (buffer.size() + dataLength > maxPacketBytes) tooLarge = true
        if (!tooLarge) buffer.write(chunk, CHUNK_HEADER_BYTES, dataLength)

        if (nextIndex < chunkCount) return Outcome(dropReason = dropped)
        val complete = buffer.toByteArray()
        val wasTooLarge = tooLarge
        reset()
        return if (wasTooLarge) {
            Outcome(dropReason = "PACKET_TOO_LARGE max=$maxPacketBytes (dropped whole, never truncated)")
        } else {
            Outcome(packet = complete, dropReason = dropped)
        }
    }

    /** Call on disconnect; returns the drop reason if a packet was cut off. */
    fun onDisconnect(): String? {
        val reason = if (inProgress) "DISCONNECTED_MID_TRANSFER got=$nextIndex/$chunkCount" else null
        reset()
        return reason
    }

    private fun reset() {
        buffer.reset()
        chunkCount = 0
        nextIndex = 0
        tooLarge = false
    }
}
