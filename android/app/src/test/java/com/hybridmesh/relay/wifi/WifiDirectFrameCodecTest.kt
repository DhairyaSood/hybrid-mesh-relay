package com.hybridmesh.relay.wifi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectFrameCodecTest {
    @Test
    fun helloHandshakeFieldsRoundTrip() {
        val expected = WifiDirectFrameCodec.Hello(
            nodeId = "HMR-00000000-0000-0000-000000000001",
            deviceName = "Relay phone",
            protocolVersion = 2,
            canRelay = true,
            canStoreForward = false
        )
        val bytes = ByteArrayOutputStream()
        WifiDirectFrameCodec.writeHello(DataOutputStream(bytes), expected)

        assertEquals(expected, WifiDirectFrameCodec.readHello(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
    }

    @Test
    fun dataAckAndProbeFramesRoundTripOverLoopbackTcp() {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { client ->
                listener.accept().use { server ->
                    val clientInput = DataInputStream(client.getInputStream())
                    val clientOutput = DataOutputStream(client.getOutputStream())
                    val serverInput = DataInputStream(server.getInputStream())
                    val serverOutput = DataOutputStream(server.getOutputStream())
                    val payload = byteArrayOf(3, 1, 4, 1, 5, 9)

                    WifiDirectFrameCodec.writeHello(
                        clientOutput,
                        WifiDirectFrameCodec.Hello("HMR-CLIENT", "Client", 2, true, true)
                    )
                    assertEquals("HMR-CLIENT", WifiDirectFrameCodec.readHello(serverInput).nodeId)
                    WifiDirectFrameCodec.writeData(clientOutput, 42L, payload)
                    val data = WifiDirectFrameCodec.readFrame(serverInput) as WifiDirectFrameCodec.Frame.Data
                    assertEquals(42L, data.transferId)
                    assertArrayEquals(payload, data.payload)

                    WifiDirectFrameCodec.writeAck(serverOutput, 42L, accepted = true)
                    val ack = WifiDirectFrameCodec.readFrame(clientInput) as WifiDirectFrameCodec.Frame.Ack
                    assertEquals(42L, ack.transferId)
                    assertTrue(ack.accepted)

                    WifiDirectFrameCodec.writeAck(serverOutput, 43L, accepted = false)
                    val rejected = WifiDirectFrameCodec.readFrame(clientInput) as WifiDirectFrameCodec.Frame.Ack
                    assertEquals(43L, rejected.transferId)
                    assertFalse(rejected.accepted)

                    WifiDirectFrameCodec.writeProbe(serverOutput, 44L)
                    val probe = WifiDirectFrameCodec.readFrame(clientInput) as WifiDirectFrameCodec.Frame.Probe
                    assertEquals(44L, probe.transferId)
                }
            }
        }
    }

    @Test
    fun invalidFrameLengthIsRejected() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(0) }
        try {
            WifiDirectFrameCodec.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            throw AssertionError("Expected malformed frame to be rejected")
        } catch (_: IOException) {
            // Expected: invalid untrusted frame length is rejected before allocation.
        }
    }
}
