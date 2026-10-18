package com.hybridmesh.relay.messaging.mesh

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/**
 * Non-blocking per-peer send exclusion with safe idle-entry eviction.
 *
 * The registry serializes acquisition/release bookkeeping so an idle mutex cannot
 * be removed while another sender is about to acquire it. Callers that find a
 * peer busy receive null and can let the durable forwarding queue retry later.
 */
internal class PeerLockRegistry {
    private class Entry {
        val mutex = Mutex()
        var references: Int = 0
    }

    class Lease internal constructor(private val releaseAction: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseAction()
        }
    }

    private val guard = Any()
    private val entries = HashMap<String, Entry>()

    fun tryAcquire(key: String): Lease? = synchronized(guard) {
        // Neyra node IDs are compared case-insensitively throughout the mesh;
        // normalize here too so casing cannot create parallel locks for one peer.
        val normalizedKey = key.trim().lowercase(Locale.US)
        if (normalizedKey.isEmpty()) return@synchronized null
        val entry = entries.getOrPut(normalizedKey) { Entry() }
        entry.references++
        if (!entry.mutex.tryLock()) {
            entry.references--
            if (entry.references == 0 && !entry.mutex.isLocked) entries.remove(normalizedKey, entry)
            return@synchronized null
        }

        Lease {
            synchronized(guard) {
                entry.mutex.unlock()
                entry.references--
                if (entry.references == 0 && !entry.mutex.isLocked) {
                    entries.remove(normalizedKey, entry)
                }
            }
        }
    }
}
