package com.hybridmesh.relay.messaging.ble

/**
 * Owns address-scoped state for the server-side GATT link. Android's server
 * callbacks identify a device by address, so every CONNECTED transition starts
 * a fresh local session generation and every address mutation is serialized.
 */
internal class BleGattPeerSessionState<TAssembly> {
    private val lock = Any()
    private val connectionIds = LinkedHashMap<String, Long>()
    private val subscriptions = LinkedHashMap<String, Long>()
    private val assemblies = LinkedHashMap<String, TAssembly>()

    fun onConnected(
        address: String,
        connectionId: Long,
        onEstablished: (Long) -> Unit = {}
    ): Long = synchronized(lock) {
        clearAddressLocked(address)
        connectionIds[address] = connectionId
        onEstablished(connectionId)
        connectionId
    }

    /**
     * The platform connection-state query is made under the same lock as
     * admission and address-state cleanup. A delayed old DISCONNECTED callback
     * is ignored while a replacement connection is still active.
     */
    fun onDisconnected(
        address: String,
        isAddressConnected: () -> Boolean,
        onClosed: (Long) -> Unit = {}
    ): Long? = synchronized(lock) {
        if (isAddressConnected()) return@synchronized null
        val connectionId = connectionIds.remove(address) ?: return@synchronized null
        clearAddressLocked(address)
        onClosed(connectionId)
        connectionId
    }

    fun setSubscribed(address: String, subscribed: Boolean) = synchronized(lock) {
        val currentConnectionId = connectionIds[address] ?: return@synchronized
        if (subscribed) {
            subscriptions[address] = currentConnectionId
        } else if (subscriptions[address] == currentConnectionId) {
            subscriptions.remove(address)
        }
    }

    fun <R> withSubscribedSession(
        address: String,
        expectedConnectionId: Long,
        action: () -> R
    ): R? = synchronized(lock) {
        if (connectionIds[address] != expectedConnectionId ||
            subscriptions[address] != expectedConnectionId
        ) return@synchronized null
        action()
    }

    fun currentConnectionId(address: String): Long? = synchronized(lock) {
        connectionIds[address]
    }

    fun <R> withAssemblies(block: (MutableMap<String, TAssembly>) -> R): R = synchronized(lock) {
        block(assemblies)
    }

    fun removeExpiredAssemblies(cutoff: Long, lastUpdatedAt: (TAssembly) -> Long) = synchronized(lock) {
        assemblies.entries.removeIf { lastUpdatedAt(it.value) < cutoff }
    }

    fun clearForServerStop(): List<Pair<String, Long>> = synchronized(lock) {
        val active = connectionIds.entries.map { it.key to it.value }
        connectionIds.clear()
        subscriptions.clear()
        assemblies.clear()
        active
    }

    private fun clearAddressLocked(address: String) {
        val prefix = "$address|"
        assemblies.keys.removeIf { it.startsWith(prefix) }
        subscriptions.remove(address)
    }
}
