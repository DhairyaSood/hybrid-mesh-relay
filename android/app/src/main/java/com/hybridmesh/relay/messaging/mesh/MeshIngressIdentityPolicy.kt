package com.hybridmesh.relay.messaging.mesh

/** Requires a session-associated predecessor and checks direct-origin consistency. */
internal object MeshIngressIdentityPolicy {
    fun isConsistent(packet: MeshPacket, sourceNodeId: String?): Boolean {
        if (sourceNodeId.isNullOrBlank()) return false
        return packet.hopCount != 0 || sourceNodeId.equals(packet.originNodeId, ignoreCase = true)
    }
}
