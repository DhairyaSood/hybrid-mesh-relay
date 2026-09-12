package com.hybridmesh.relay.messaging.model

enum class GattTransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    SETTING_UP_NOTIFICATIONS,
    NEGOTIATING_MTU,
    READY,
    WRITING,
    WAITING_ACK,
    COMPLETED,
    DISCONNECTING,
    FAILED
}
