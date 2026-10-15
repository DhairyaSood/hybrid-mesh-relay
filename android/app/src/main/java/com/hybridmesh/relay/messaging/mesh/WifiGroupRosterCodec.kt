package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.data.NodeIdGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Small bounded roster shared by a Wi-Fi Direct group owner with its active clients. */
internal object WifiGroupRosterCodec {
    const val MESSAGE_TYPE = "WIFI_GROUP_ROSTER_V1"

    private const val MAGIC = 0x4E595247 // NYRG
    private const val VERSION = 1
    private const val MAX_ENTRIES = 8
    private const val MAX_NODE_ID_BYTES = 64
    private const val MAX_DEVICE_NAME_BYTES = 256
    private const val MAX_ENCODED_BYTES = 8 * 1024

    data class Roster(val revision: Long, val peers: List<WifiGroupRosterEntry>)

    fun encode(revision: Long, peers: List<WifiGroupRosterEntry>): String {
        require(revision > 0L)
        require(peers.size <= MAX_ENTRIES)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.writeLong(revision)
            data.writeByte(peers.size)
            peers.forEach { peer ->
                val nodeId = peer.nodeId.toByteArray(Charsets.UTF_8)
                val deviceName = peer.deviceName.trim().take(80).toByteArray(Charsets.UTF_8)
                require(NodeIdGenerator.isValid(peer.nodeId))
                require(nodeId.size in 1..MAX_NODE_ID_BYTES)
                require(deviceName.size in 1..MAX_DEVICE_NAME_BYTES)
                data.writeByte(nodeId.size)
                data.write(nodeId)
                data.writeShort(deviceName.size)
                data.write(deviceName)
                data.writeBoolean(peer.canRelay)
                data.writeBoolean(peer.canStoreForward)
            }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_ENCODED_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): Roster? {
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_BYTES * 2) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            if (bytes.size > MAX_ENCODED_BYTES) return null
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != MAGIC || data.readUnsignedByte() != VERSION) return null
                val revision = data.readLong()
                val count = data.readUnsignedByte()
                if (revision <= 0L || count > MAX_ENTRIES) return null
                val peers = buildList {
                    repeat(count) {
                        val nodeIdLength = data.readUnsignedByte()
                        if (nodeIdLength !in 1..MAX_NODE_ID_BYTES) return null
                        val nodeIdBytes = ByteArray(nodeIdLength)
                        data.readFully(nodeIdBytes)
                        val nodeId = nodeIdBytes.toString(Charsets.UTF_8)
                        val nameLength = data.readUnsignedShort()
                        if (nameLength !in 1..MAX_DEVICE_NAME_BYTES) return null
                        val nameBytes = ByteArray(nameLength)
                        data.readFully(nameBytes)
                        val deviceName = nameBytes.toString(Charsets.UTF_8).trim()
                        val canRelay = data.readBoolean()
                        val canStoreForward = data.readBoolean()
                        if (!NodeIdGenerator.isValid(nodeId) || deviceName.isBlank()) return null
                        add(WifiGroupRosterEntry(nodeId, deviceName, canRelay, canStoreForward))
                    }
                }
                if (data.available() != 0 || peers.map { it.nodeId.lowercase() }.distinct().size != peers.size) return null
                Roster(revision, peers)
            }
        }.getOrNull()
    }
}

internal data class WifiGroupRosterEntry(
    val nodeId: String,
    val deviceName: String,
    val canRelay: Boolean,
    val canStoreForward: Boolean
)
