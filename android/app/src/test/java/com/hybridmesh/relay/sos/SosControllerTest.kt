package com.hybridmesh.relay.sos

import com.hybridmesh.relay.location.SosFix
import com.hybridmesh.relay.location.SosLocationResult
import com.hybridmesh.relay.messaging.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosControllerTest {
    private class FakeSender : SosSender {
        val sent = mutableListOf<Pair<String, String>>()
        private var counter = 0
        override suspend fun send(peerNodeId: String, text: String): String {
            val id = "m${++counter}"
            sent += peerNodeId to text
            ids += id to peerNodeId
            return id
        }
        val ids = mutableListOf<Pair<String, String>>()
        fun alertsTo(peer: String) = sent.filter { it.first == peer && !it.second.contains("CANCELLED") }
        fun cancelsTo(peer: String) = sent.filter { it.first == peer && it.second.contains("CANCELLED") }
        fun idsFor(peer: String) = ids.filter { it.second == peer }.map { it.first }
    }

    private class Env(scope: CoroutineScope, test: TestScope) {
        val sender = FakeSender()
        var targets = setOf("A", "B")
        val statuses = mutableMapOf<String, String>()
        val controller = SosController(
            scope = scope,
            sender = sender,
            targets = { targets },
            statuses = { statuses },
            locate = {
                SosLocationResult(SosFix(12.9716, 77.5946, 8f, test.currentTime, "gps"), 0L, null, 0L)
            },
            clock = { test.currentTime }
        )
    }

    // Test dispatcher scope so virtual time drives delay().
    private fun runSos(block: suspend TestScope.(Env) -> Unit) = runTest(StandardTestDispatcher()) {
        val e = Env(backgroundScope, this)
        block(e)
    }

    private fun TestScope.at(seconds: Long) {
        testScheduler.advanceTimeBy(seconds * 1000 - currentTime + 1)
        testScheduler.runCurrent()
    }

    @Test
    fun cancelDuringCountdownSendsNothing() = runSos { e ->
        e.controller.start()
        at(3)
        assertEquals(SosPhase.COUNTDOWN, e.controller.state.value.phase)
        e.controller.cancel()
        at(30)
        assertTrue(e.sender.sent.isEmpty())
        assertEquals(SosPhase.IDLE, e.controller.state.value.phase)
    }

    @Test
    fun initialFanOutAfterCountdown() = runSos { e ->
        e.controller.start()
        at(4)
        assertTrue(e.sender.sent.isEmpty())
        at(6)
        assertEquals(setOf("A", "B"), e.sender.sent.map { it.first }.toSet())
        assertEquals(SosPhase.ACTIVE, e.controller.state.value.phase)
        assertTrue(e.sender.sent.first().second.startsWith("SOS v1 | 12.971600,77.594600"))
    }

    @Test
    fun dedupesRepeatedTicks() = runSos { e ->
        e.controller.start()
        at(5 + 45)
        assertEquals(1, e.sender.alertsTo("A").size)
        assertEquals(1, e.sender.alertsTo("B").size)
    }

    @Test
    fun lateJoinerAtThirtySecondsGetsAlert() = runSos { e ->
        e.controller.start()
        at(5 + 25)
        e.targets = setOf("A", "B", "C")
        at(5 + 29)
        assertTrue(e.sender.alertsTo("C").isEmpty())
        at(5 + 31)
        assertEquals(1, e.sender.alertsTo("C").size)
        assertEquals(3, e.controller.state.value.sent)
    }

    @Test
    fun nothingNewAfterSixtySeconds() = runSos { e ->
        e.controller.start()
        at(5 + 61)
        e.targets = setOf("A", "B", "LATE")
        at(5 + 200)
        assertTrue(e.sender.alertsTo("LATE").isEmpty())
        assertTrue(e.controller.state.value.windowClosed)
    }

    @Test
    fun failedCopyIsResentExactlyOnce() = runSos { e ->
        e.controller.start()
        at(5 + 1)
        e.statuses[e.sender.idsFor("A").last()] = DeliveryStatus.FAILED.name
        at(5 + 11)
        assertEquals(2, e.sender.alertsTo("A").size)
        e.statuses[e.sender.idsFor("A").last()] = DeliveryStatus.FAILED.name
        at(5 + 41)
        assertEquals(2, e.sender.alertsTo("A").size)
        assertEquals(1, e.sender.alertsTo("B").size)
    }

    @Test
    fun stopSendsCancelledToRecipientsAndStopsTicker() = runSos { e ->
        e.controller.start()
        at(5 + 12)
        e.controller.stop()
        runCurrent()
        assertEquals(SosPhase.STOPPED, e.controller.state.value.phase)
        assertEquals(1, e.sender.cancelsTo("A").size)
        assertEquals(1, e.sender.cancelsTo("B").size)
        assertTrue(e.sender.cancelsTo("A").first().second.startsWith("SOS v1 CANCELLED"))
        val count = e.sender.sent.size
        e.targets = setOf("A", "B", "C")
        at(5 + 100)
        assertEquals(count, e.sender.sent.size)
    }

    @Test
    fun countsFollowStatuses() = runSos { e ->
        e.targets = setOf("A", "B", "C", "D")
        e.controller.start()
        at(5 + 1)
        e.statuses[e.sender.idsFor("A").single()] = DeliveryStatus.DELIVERED.name
        e.statuses[e.sender.idsFor("B").single()] = DeliveryStatus.IN_FLIGHT.name
        e.statuses[e.sender.idsFor("C").single()] = DeliveryStatus.FAILED.name
        at(5 + 3)
        val s = e.controller.state.value
        assertEquals(4, s.sent)
        assertEquals(1, s.delivered)
        assertEquals(2, s.pending)
        assertEquals(1, s.failed)
        at(5 + 62)
        // C was resent once and is QUEUED again, so it is pending, not failed.
        assertEquals(3, e.controller.state.value.unconfirmed)
    }
}
