package com.hybridmesh.relay.model

enum class MessageStatus {
    QUEUED,
    SENDING,
    RELAYING,
    DELIVERED,
    FAILED
}