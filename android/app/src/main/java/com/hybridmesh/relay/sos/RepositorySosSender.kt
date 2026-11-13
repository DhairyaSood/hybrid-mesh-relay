package com.hybridmesh.relay.sos

import com.hybridmesh.relay.data.NodeIdGenerator
import com.hybridmesh.relay.messaging.data.MessagingRepository
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import com.hybridmesh.relay.model.MessageType
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Queues SOS texts through the regular outgoing path; the mesh engine delivers them. */
class RepositorySosSender(
    private val repository: MessagingRepository,
    private val localNodeId: () -> String
) : SosSender {
    override suspend fun send(peerNodeId: String, text: String): String? {
        val nodeId = peerNodeId.trim().uppercase(Locale.US)
        val message = text.trim()
        if (
            message.isBlank() ||
            !NodeIdGenerator.isValid(nodeId) ||
            nodeId.equals(localNodeId(), ignoreCase = true) ||
            message.toByteArray(Charsets.UTF_8).size > MeshPacketCodec.MAX_TEXT_BYTES
        ) return null
        return try {
            repository.addOutgoing(message, nodeId, MessageType.EMERGENCY.name).messageId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
