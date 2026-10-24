package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity

/** Converts durable relay rows back to packets and validates their next-hop wire shape. */
internal object MeshForwardingRecordCodec {
    fun toPacket(record: MeshForwardingRecordEntity): MeshPacket? {
        val type = MeshPacket.PacketType.entries.firstOrNull { it.code.toInt() == record.packetType }
            ?: return null
        val packet = runCatching {
            MeshPacket(
                packetId = record.packetId,
                messageId = record.messageId,
                originNodeId = record.originNodeId,
                destinationNodeId = record.destinationNodeId,
                createdAt = record.createdAt,
                expiresAt = record.expiresAt,
                ttl = record.ttl,
                hopCount = record.hopCount,
                packetType = type,
                messageType = record.messageType,
                content = record.content,
                deliveryHopCount = record.deliveryHopCount,
                routeTransportCodes = record.routeTrace.split(",")
                    .mapNotNull { it.toIntOrNull() }
                    .take(MeshPacketCodec.INITIAL_TTL + 1),
                routeTraceComplete = record.routeTraceComplete
            )
        }.getOrNull() ?: return null

        // DATA relay rows include the ingress link's transport code, while hopCount
        // is advanced only when the worker prepares the outbound packet. Validate
        // that complete trace against that outbound shape without changing the row
        // packet returned to the worker.
        val packetForValidation = if (
            packet.packetType == MeshPacket.PacketType.DATA &&
            packet.routeTraceComplete &&
            packet.routeTransportCodes.size == packet.hopCount + 1
        ) {
            packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
        } else {
            packet
        }

        return runCatching {
            MeshPacketCodec.encode(packetForValidation)
            packet
        }.getOrNull()
    }
}
