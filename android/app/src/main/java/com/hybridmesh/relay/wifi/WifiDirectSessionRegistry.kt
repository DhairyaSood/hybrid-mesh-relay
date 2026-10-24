package com.hybridmesh.relay.wifi

/** Atomic logical-peer admission for the multiple TCP sessions in one P2P group. */
internal class WifiDirectSessionRegistry<T>(
    private val maxSessions: Int,
    private val addressOf: (T) -> String
) {
    internal data class Admission<T>(
        val accepted: Boolean,
        val replaced: T? = null
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, T>()

    val values: List<T>
        get() = synchronized(lock) { sessions.values.toList() }

    val size: Int
        get() = synchronized(lock) { sessions.size }

    fun isEmpty(): Boolean = synchronized(lock) { sessions.isEmpty() }

    operator fun get(nodeId: String): T? = synchronized(lock) { sessions[nodeId] }

    fun admit(nodeId: String, address: String, session: T): Admission<T> = synchronized(lock) {
        val existing = sessions[nodeId]
        when (
            WifiDirectAdmissionPolicy.sessionAction(
                sessionExists = existing != null,
                existingAddress = existing?.let(addressOf),
                incomingAddress = address,
                sessionCount = sessions.size,
                maxSessions = maxSessions
            )
        ) {
            WifiDirectAdmissionPolicy.SessionAction.ACCEPT_NEW -> {
                sessions[nodeId] = session
                Admission(accepted = true)
            }
            WifiDirectAdmissionPolicy.SessionAction.REPLACE_SAME_PEER -> {
                Admission(accepted = true, replaced = sessions.put(nodeId, session))
            }
            WifiDirectAdmissionPolicy.SessionAction.REJECT_CAPACITY,
            WifiDirectAdmissionPolicy.SessionAction.REJECT_ADDRESS_CONFLICT -> Admission(accepted = false)
        }
    }

    /**
     * A stale socket close cannot remove or report a newer session as disconnected.
     * Run the disconnect event while admission is excluded so a reconnect cannot
     * publish SESSION_READY before this session publishes its stale close event.
     */
    fun remove(
        nodeId: String,
        expectedSession: T,
        onRemoved: () -> Unit = {}
    ): Boolean = synchronized(lock) {
        if (sessions[nodeId] !== expectedSession) return@synchronized false
        sessions.remove(nodeId)
        onRemoved()
        true
    }

    /** Serialize setup completion with replacement so stale handshakes cannot publish readiness. */
    fun runIfCurrent(nodeId: String, expectedSession: T, action: () -> Unit): Boolean = synchronized(lock) {
        if (sessions[nodeId] !== expectedSession) return@synchronized false
        action()
        true
    }
}
