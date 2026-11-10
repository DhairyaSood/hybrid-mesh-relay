package com.hybridmesh.relay.messaging.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.hybridmesh.relay.messaging.model.GattTransportError
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connect-phase lifecycle of [BleGattClient] against faked Bluetooth objects.
 * Virtual time (runTest) drives the 8 s connect timeout without real waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGattClientConnectLifecycleTest {
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private val callback = slot<BluetoothGattCallback>()
    private val client = BleGattClient(mockk<Context>(relaxed = true))

    @Before
    fun setUp() {
        every { device.address } returns "AA:BB:CC:DD:EE:FF"
        every { gatt.device } returns device
        every { device.connectGatt(any(), any(), capture(callback), any()) } returns gatt
        assertEquals("no GATT handle may be open before a test", 0, openHandleCount())
    }

    private suspend fun send() = client.sendPayload(
        device = device,
        payload = byteArrayOf(1, 2, 3),
        messageId = "msg-1",
        runtimeGeneration = 1L,
        isRuntimeGenerationCurrent = { true }
    )

    private fun BleGattClient.GattSendResult.assertConnectFailed() {
        assertTrue("expected Failed, got $this", this is BleGattClient.GattSendResult.Failed)
        assertEquals(GattTransportError.CONNECTION_FAILED, (this as BleGattClient.GattSendResult.Failed).error)
    }

    // (1) Failed or timed-out connects close the handle and the open count returns to 0.

    @Test
    fun failedConnectReportedAfterConnectGattReturnsClosesHandle() = runTest {
        val result = async { send() }
        runCurrent()
        assertEquals(1, openHandleCount())

        callback.captured.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)

        result.await().assertConnectFailed()
        verify(atLeast = 1) { gatt.close() }
        assertEquals(0, openHandleCount())
    }

    @Test
    fun failedConnectReportedBeforeConnectGattReturnsClosesHandle() = runTest {
        // Some stacks report the failure synchronously, before connectGatt hands back the handle.
        every { device.connectGatt(any(), any(), any(), any()) } answers {
            (args[2] as BluetoothGattCallback)
                .onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
            gatt
        }

        send().assertConnectFailed()
        verify(atLeast = 1) { gatt.close() }
        assertEquals(0, openHandleCount())
    }

    @Test
    fun timedOutConnectClosesHandle() = runTest {
        // connectGatt returns but the stack never calls back.
        send().assertConnectFailed()
        verify(atLeast = 1) { gatt.close() }
        assertEquals(0, openHandleCount())
    }

    // (2) A connect timeout is a failure; real cancellation is still re-thrown.

    @Test
    fun connectTimeoutIsReportedAsFailureNotCancellation() = runTest {
        val start = currentTime
        val result = send() // would throw CancellationException if the timeout were mis-handled

        result.assertConnectFailed()
        assertEquals(BleGattConstants.CONNECT_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun cancellingTheCallerDuringConnectRethrowsCancellation() = runTest {
        var returned: BleGattClient.GattSendResult? = null
        val job = launch { returned = send() }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull("cancellation must propagate, not become a send result", returned)
        verify(atLeast = 1) { gatt.close() }
        assertEquals(0, openHandleCount())
    }

    @Test
    fun outerTimeoutDuringConnectIsNotSwallowedAsConnectFailure() = runTest {
        // The caller's own (shorter) timeout is real cancellation of the caller and must propagate,
        // even though it surfaces inside connect() as a TimeoutCancellationException.
        val result = withTimeoutOrNull(1_000L) { send() }

        assertNull("outer timeout must cancel the send, got $result", result)
        verify(atLeast = 1) { gatt.close() }
        assertEquals(0, openHandleCount())
    }

    @Test
    fun handleStaysOpenWhileConnectIsStillPending() = runTest {
        val job = launch { send() }
        advanceTimeBy(BleGattConstants.CONNECT_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(1, openHandleCount())
        verify(exactly = 0) { gatt.close() }
        job.cancel()
        job.join()
    }

    /** Reads BleGattClient's private open-handle registry (backs the MESHDBG open= count). */
    private fun openHandleCount(): Int {
        val field = BleGattClient::class.java.getDeclaredField("openGattHandles")
        field.isAccessible = true
        val handles = field.get(null) as MutableSet<*>
        return synchronized(handles) { handles.size }
    }
}
