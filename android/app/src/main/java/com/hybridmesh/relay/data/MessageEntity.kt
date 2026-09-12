package com.hybridmesh.relay.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hybridmesh.relay.model.Message
import com.hybridmesh.relay.model.MessageStatus
import com.hybridmesh.relay.model.MessageType
import com.hybridmesh.relay.model.RouteInfo

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,

    val senderId: String,

    val recipientId: String,

    val content: String,

    val timestamp: Long,

    val type: String,

    val status: String,

    val routeTransport: String?,

    val routeHopCount: Int?,

    val routeNextHopNodeId: String?
) {

    fun toMessage(): Message {
        val route = if (routeTransport != null) {
            RouteInfo(
                transport = routeTransport,
                hopCount = routeHopCount ?: 0,
                nextHopNodeId = routeNextHopNodeId
            )
        } else {
            null
        }

        return Message(
            id = id,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            timestamp = timestamp,
            type = MessageType.valueOf(type),
            status = MessageStatus.valueOf(status),
            route = route
        )
    }

    companion object {

        fun fromMessage(message: Message): MessageEntity {
            return MessageEntity(
                id = message.id,
                senderId = message.senderId,
                recipientId = message.recipientId,
                content = message.content,
                timestamp = message.timestamp,
                type = message.type.name,
                status = message.status.name,
                routeTransport = message.route?.transport,
                routeHopCount = message.route?.hopCount,
                routeNextHopNodeId = message.route?.nextHopNodeId
            )
        }
    }
}