package com.hybridmesh.relay.messaging.mesh

import com.hybridmesh.relay.messaging.data.MeshForwardingRecordEntity

/** Converts packets accepted for relay to durable forwarding rows and back. */
internal object MeshForwardingRecordCodec {
    /**
     * A relay records the link a DATA packet arrived on in its route trace. The
     * hop count is advanced only when the forwarding worker builds the outbound copy.
     */
    fun withObservedIngress(packet: MeshPacket, sourceTransport: MeshTransportKind): MeshPacket =
        if (packet.packetType == MeshPacket.PacketType.DATA) {
            packet.copy(
                routeTransportCodes = (packet.routeTransportCodes + sourceTransport.traceCode)
                    .take(MeshPacketCodec.INITIAL_TTL + 1)
            )
        } else {
            packet
        }

    fun toRecord(
        packet: MeshPacket,
        predecessor: String?,
        predecessorAddress: String?,
        nextAttemptAt: Long
    ): MeshForwardingRecordEntity =
        MeshForwardingRecordEntity(
            packetId = packet.packetId,
            messageId = packet.messageId,
            originNodeId = packet.originNodeId,
            destinationNodeId = packet.destinationNodeId,
            receivedFromNodeId = predecessor,
            receivedFromAddress = predecessorAddress,
            createdAt = packet.createdAt,
            expiresAt = packet.expiresAt,
            ttl = packet.ttl,
            hopCount = packet.hopCount,
            packetType = packet.packetType.code.toInt(),
            messageType = packet.messageType,
            content = packet.content,
            deliveryHopCount = packet.deliveryHopCount,
            state = "PENDING",
            attemptCount = 0,
            nextAttemptAt = nextAttemptAt,
            lastError = null,
            routeTrace = packet.routeTransportCodes.joinToString(","),
            routeTraceComplete = packet.routeTraceComplete
        )

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

        // A stored DATA row already carries the ingress link in its route trace
        // (withObservedIngress), so a complete trace has hopCount + 1 codes until the
        // worker builds the outbound copy. Validating the row as-is fails the codec's
        // "one transport per prior hop" rule and drops every relayed DATA packet, so
        // validate the outbound shape instead while returning the row packet unchanged.
        val packetForValidation = if (
            packet.packetType == MeshPacket.PacketType.DATA &&
            packet.ttl > 0 &&
            packet.routeTraceComplete &&
            packet.routeTransportCodes.size == packet.hopCount + 1
        ) {
            outbound(packet)
        } else {
            packet
        }
        return runCatching { MeshPacketCodec.encode(packetForValidation); packet }.getOrNull()
    }

    /** The copy the forwarding worker transmits: one more hop, one less TTL. */
    fun outbound(packet: MeshPacket): MeshPacket =
        packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
}
