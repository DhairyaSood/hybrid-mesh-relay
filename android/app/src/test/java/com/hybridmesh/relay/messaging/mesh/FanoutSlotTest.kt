package com.hybridmesh.relay.messaging.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fan-out slot behaviour used by MeshEngine.sendPacketToPeers, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class FanoutSlotTest {
    private data class Outcome(val accepted: Boolean)

    private val slotWaitMs = 2_000L

    // (3a) With the pool full, a peer waits (up to 2 s) and then gets a slot.

    @Test
    fun peerWaitsForFullPoolAndGetsSlotWhenOneFrees() = runTest {
        val pool = Semaphore(1)
        val busy = FanoutSlot(pool)
        assertTrue(busy.acquire(slotWaitMs))

        val waiter = FanoutSlot(pool)
        val acquired = async { waiter.acquire(slotWaitMs) }
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse("must still be waiting, not dropped", acquired.isCompleted)

        busy.release()

        assertTrue(acquired.await())
        assertEquals(1_500L, currentTime)
        assertEquals(0, pool.availablePermits)
        waiter.release()
        assertEquals(1, pool.availablePermits)
    }

    @Test
    fun peerGivesUpAfterTwoSecondsWithoutLeakingAPermit() = runTest {
        val pool = Semaphore(1)
        val busy = FanoutSlot(pool)
        assertTrue(busy.acquire(slotWaitMs))

        assertFalse(FanoutSlot(pool).acquire(slotWaitMs))

        assertEquals(slotWaitMs, currentTime)
        assertEquals(0, pool.availablePermits)
        busy.release()
        assertEquals(1, pool.availablePermits)
    }

    // (3b) When one peer accepts, the other attempts release their slots.

    @Test
    fun acceptanceReleasesEveryAttemptsSlot() = runTest {
        val pool = Semaphore(3)
        val completion = CompletableDeferred<Outcome>()
        val slots = List(3) { FanoutSlot(pool).also { it.releaseWhen(completion) { o -> o.accepted } } }
        slots.forEach { assertTrue(it.acquire(slotWaitMs)) }
        assertEquals(0, pool.availablePermits)

        completion.complete(Outcome(accepted = true))

        assertEquals(3, pool.availablePermits)
        // Attempts that finish later release again; that must not over-release the pool.
        slots.forEach(FanoutSlot::release)
        assertEquals(3, pool.availablePermits)
    }

    @Test
    fun allPeersFailingDoesNotReleaseEarly() = runTest {
        val pool = Semaphore(2)
        val completion = CompletableDeferred<Outcome>()
        val slots = List(2) { FanoutSlot(pool).also { it.releaseWhen(completion) { o -> o.accepted } } }
        slots.forEach { assertTrue(it.acquire(slotWaitMs)) }

        completion.complete(Outcome(accepted = false))

        assertEquals(0, pool.availablePermits)
        slots.forEach(FanoutSlot::release)
        assertEquals(2, pool.availablePermits)
    }

    @Test
    fun peerStillWaitingWhenAnotherAcceptsHandsItsPermitBack() = runTest {
        val pool = Semaphore(1)
        val busy = FanoutSlot(pool)
        assertTrue(busy.acquire(slotWaitMs))

        val completion = CompletableDeferred<Outcome>()
        val waiter = FanoutSlot(pool).also { it.releaseWhen(completion) { o -> o.accepted } }
        val acquired = async { waiter.acquire(slotWaitMs) }
        runCurrent()

        completion.complete(Outcome(accepted = true)) // accepted elsewhere while waiting
        busy.release()                                 // permit now reaches the waiter

        assertFalse("a slot obtained after acceptance must not be kept", acquired.await())
        assertEquals(1, pool.availablePermits)
    }
}
