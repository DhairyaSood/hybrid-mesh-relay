package com.hybridmesh.relay.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosLocationProviderTest {
    private class FakeSource(
        var permission: Boolean = true,
        var enabled: Boolean = true,
        var fresh: SosFix? = null,
        var freshDelayMs: Long = 0L,
        var lastKnown: List<SosFix> = emptyList()
    ) : SosLocationSource {
        override fun permissionGranted() = permission
        override fun locationEnabled() = enabled
        override suspend fun freshFix(): SosFix? {
            delay(freshDelayMs)
            return fresh
        }
        override fun lastKnownFixes() = lastKnown
    }

    private fun fix(time: Long, provider: String = "network") = SosFix(1.0, 2.0, 10f, time, provider)

    private fun TestScope.provider(source: FakeSource, now: Long = 1_000_000L) =
        SosLocationProvider(source, clock = { now }, freshTimeoutMs = 8_000L)

    @Test
    fun freshFix() = runTest {
        val result = provider(FakeSource(fresh = fix(1_000_000L))).locate()
        assertNotNull(result.fix)
        assertEquals(0L, result.ageMs)
        assertNull(result.reason)
        assertNull(result.clockSkewMs)
    }

    @Test
    fun staleFallbackUsesNewestWithAge() = runTest {
        val source = FakeSource(
            freshDelayMs = 20_000L,
            fresh = fix(1_000_000L),
            lastKnown = listOf(fix(900_000L, "gps"), fix(950_000L), fix(800_000L, "passive"))
        )
        val result = provider(source).locate()
        assertEquals(950_000L, result.fix!!.timeMillis)
        assertEquals(50_000L, result.ageMs)
    }

    @Test
    fun permissionDenied() = runTest {
        val result = provider(FakeSource(permission = false, fresh = fix(1_000_000L))).locate()
        assertNull(result.fix)
        assertEquals("permission denied", result.reason)
    }

    @Test
    fun locationOff() = runTest {
        val result = provider(FakeSource(enabled = false, fresh = fix(1_000_000L))).locate()
        assertNull(result.fix)
        assertEquals("location off", result.reason)
    }

    @Test
    fun timeoutWithNoLastKnownIsNoFix() = runTest {
        val result = provider(FakeSource(freshDelayMs = 20_000L, fresh = fix(1_000_000L))).locate()
        assertNull(result.fix)
        assertEquals("no fix", result.reason)
    }

    @Test
    fun timeoutIsEightSeconds() = runTest {
        val source = FakeSource(freshDelayMs = 8_001L, fresh = fix(1_000_000L), lastKnown = listOf(fix(990_000L)))
        val scope = this
        var done = false
        val job = scope.launchLocate(source) { done = true }
        testScheduler.advanceTimeBy(7_999L)
        assertFalse(done)
        testScheduler.advanceTimeBy(2L)
        testScheduler.runCurrent()
        assertTrue(done)
        job.join()
    }

    @Test
    fun clockSkewDetectedFromFreshGpsFix() = runTest {
        val skewed = provider(FakeSource(fresh = fix(1_000_000L + 120_000L, "gps"))).locate()
        assertEquals(120_000L, skewed.clockSkewMs)
        assertTrue(skewed.clockSkewSuspect)

        val fine = provider(FakeSource(fresh = fix(1_000_000L + 5_000L, "gps"))).locate()
        assertFalse(fine.clockSkewSuspect)

        val network = provider(FakeSource(fresh = fix(1_000_000L + 120_000L, "network"))).locate()
        assertNull(network.clockSkewMs)
    }

    private fun TestScope.launchLocate(source: FakeSource, onDone: () -> Unit) =
        launch(StandardTestDispatcher(testScheduler)) {
            provider(source).locate()
            onDone()
        }
}
