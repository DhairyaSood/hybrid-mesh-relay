package com.hybridmesh.relay.data

import com.hybridmesh.relay.model.Message
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val store: MessageStore
) {

    suspend fun save(message: Message) {
        store.save(message)
    }

    fun observeMessages(): Flow<List<Message>> {
        return store.observeMessages()
    }

    suspend fun getMessage(
        messageId: String
    ): Message? {
        return store.getMessage(messageId)
    }

    suspend fun updateStatus(
        messageId: String,
        status: String
    ) {
        store.updateStatus(
            messageId = messageId,
            status = status
        )
    }

    suspend fun delete(message: Message) {
        store.delete(message)
    }

    suspend fun deleteAll() {
        store.deleteAll()
    }
}