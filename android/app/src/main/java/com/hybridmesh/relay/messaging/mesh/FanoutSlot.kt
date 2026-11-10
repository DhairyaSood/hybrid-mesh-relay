package com.hybridmesh.relay.messaging.mesh

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One fan-out permit for one peer attempt. Release is idempotent and may happen
 * before acquire completes (e.g. another peer already accepted); a permit obtained
 * after that point is handed straight back.
 */
internal class FanoutSlot(private val semaphore: Semaphore) {
    // 0 = not held, 1 = held, 2 = released
    private val state = AtomicInteger(0)

    suspend fun acquire(timeoutMs: Long): Boolean {
        var acquired = false
        withTimeoutOrNull(timeoutMs) {
            semaphore.acquire()
            acquired = true
        }
        if (!acquired) return false
        if (!state.compareAndSet(0, 1)) {
            semaphore.release()
            return false
        }
        return true
    }

    fun release() {
        if (state.compareAndSet(1, 2)) semaphore.release() else state.compareAndSet(0, 2)
    }

    /** Gives this slot back as soon as [completion] finishes with a result matching [shouldRelease]. */
    fun <T> releaseWhen(completion: Deferred<T>, shouldRelease: (T) -> Boolean) {
        completion.invokeOnCompletion {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            if (runCatching { shouldRelease(completion.getCompleted()) }.getOrDefault(false)) release()
        }
    }
}
