package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hybridmesh.relay.data.AppDatabase
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.data.MessageRepository
import com.hybridmesh.relay.data.RoomMessageStore
import com.hybridmesh.relay.model.Message
import com.hybridmesh.relay.model.MessageStatus
import com.hybridmesh.relay.model.MessageType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MessagesViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val database =
        AppDatabase.getInstance(application)

    private val repository =
        MessageRepository(
            RoomMessageStore(
                database.messageDao()
            )
        )

    private val identityStore =
        IdentityStore(application)

    val messages: StateFlow<List<Message>> =
        repository
            .observeMessages()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(
                    5_000
                ),
                initialValue = emptyList()
            )

    fun sendMessage(
        recipientId: String,
        content: String,
        type: MessageType,
        onSaved: () -> Unit = {}
    ) {
        val cleanedRecipient =
            recipientId.trim()

        val cleanedContent =
            content.trim()

        if (
            cleanedRecipient.isBlank() ||
            cleanedContent.isBlank()
        ) {
            return
        }

        val localIdentity =
            identityStore.getIdentity()

        val message = Message(
            id = UUID.randomUUID().toString(),
            senderId = localIdentity.nodeId,
            recipientId = cleanedRecipient,
            content = cleanedContent,
            timestamp = System.currentTimeMillis(),
            type = type,
            status = MessageStatus.QUEUED,
            route = null
        )

        viewModelScope.launch {
            repository.save(message)
            onSaved()
        }
    }

    fun deleteAllMessages() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }
}