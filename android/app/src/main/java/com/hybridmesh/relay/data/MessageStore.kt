package com.hybridmesh.relay.data

import com.hybridmesh.relay.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface MessageStore {

    suspend fun save(message: Message)

    fun observeMessages(): Flow<List<Message>>

    suspend fun getMessage(messageId: String): Message?

    suspend fun updateStatus(
        messageId: String,
        status: String
    )

    suspend fun delete(message: Message)

    suspend fun deleteAll()
}

class RoomMessageStore(
    private val dao: MessageDao
) : MessageStore {

    override suspend fun save(message: Message) {
        dao.insert(
            MessageEntity.fromMessage(message)
        )
    }

    override fun observeMessages(): Flow<List<Message>> {
        return dao.observeMessages().map { entities ->
            entities.map { it.toMessage() }
        }
    }

    override suspend fun getMessage(
        messageId: String
    ): Message? {
        return dao.getById(messageId)?.toMessage()
    }

    override suspend fun updateStatus(
        messageId: String,
        status: String
    ) {
        dao.updateStatus(
            messageId = messageId,
            status = status
        )
    }

    override suspend fun delete(
        message: Message
    ) {
        dao.delete(
            MessageEntity.fromMessage(message)
        )
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}