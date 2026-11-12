package com.hybridmesh.relay.ble

import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDiscoveryCodecTest {
    private val localNodeId = "HMR-00000000-0000-0000-0000-0000000000aa"

    @Test
    fun advertiserPayloadRoundTripsThroughScannerParser() {
        val identity = LocalIdentity(
            nodeId = "HMR-3f2b8c1e-9d4a-4e6f-8a1b-2c3d4e5f6a7b",
            deviceName = "Charlie",
            deviceType = NodeType.PHONE
        )

        val payload = BleDiscoveryCodec.encode(identity)
        val decoded = BleDiscoveryCodec.decode(payload, localNodeId)

        assertTrue("payload rejected: $decoded", decoded is BleDiscoveryCodec.DecodeResult.Parsed)
        val discovery = (decoded as BleDiscoveryCodec.DecodeResult.Parsed).discovery
        assertEquals(identity.nodeId, discovery.nodeId)
        assertEquals(NodeType.PHONE, discovery.deviceType)
        assertEquals(BleConstants.DISCOVERY_VERSION.toInt(), discovery.discoveryVersion)
        assertEquals(BleConstants.MESH_PROTOCOL_VERSION.toInt(), discovery.meshProtocolVersion)
        assertTrue(discovery.canRelay)
        assertTrue(discovery.canStoreForward)
        assertTrue(discovery.supportsAttachmentHopIdentity)
        // The advertiser truncates to the 6-byte mesh name budget.
        assertEquals("Charli", discovery.deviceName)
    }

    @Test
    fun relayDeviceTypeAndUppercaseNodeIdRoundTrip() {
        val identity = LocalIdentity(
            nodeId = "HMR-3F2B8C1E-9D4A-4E6F-8A1B-2C3D4E5F6A7B",
            deviceName = "R1",
            deviceType = NodeType.RELAY
        )

        val decoded = BleDiscoveryCodec.decode(BleDiscoveryCodec.encode(identity), localNodeId)
            as BleDiscoveryCodec.DecodeResult.Parsed

        // UUID parsing canonicalizes to lowercase; mesh code compares node IDs case-insensitively.
        assertTrue(identity.nodeId.equals(decoded.discovery.nodeId, ignoreCase = true))
        assertEquals(NodeType.RELAY, decoded.discovery.deviceType)
    }

    @Test
    fun maximumNicknameStillFitsLegacyScanResponse() {
        val identity = LocalIdentity(
            nodeId = "HMR-${UUID.randomUUID()}",
            deviceName = "a".repeat(50),
            deviceType = NodeType.PHONE
        )

        val payload = BleDiscoveryCodec.encode(identity)

        // AD length(1) + AD type(1) + manufacturer id(2) + payload must fit 31 bytes.
        assertEquals(BleConstants.DISCOVERY_MAX_BYTES, payload.size)
        assertTrue("scan response is ${4 + payload.size} bytes", 4 + payload.size <= 31)
        assertTrue(BleDiscoveryCodec.decode(payload, localNodeId) is BleDiscoveryCodec.DecodeResult.Parsed)
    }

    @Test
    fun scannerStillAcceptsEightByteNamesFromV4Peers() {
        val nodeId = UUID.randomUUID()
        val name = "abcdefgh".toByteArray()
        val payload = ByteBuffer.allocate(BleConstants.DISCOVERY_BASE_BYTES + name.size)
            .put(BleConstants.DISCOVERY_VERSION)
            .putLong(nodeId.mostSignificantBits)
            .putLong(nodeId.leastSignificantBits)
            .put(BleConstants.DEVICE_TYPE_PHONE)
            .put(0x03)
            .put(BleConstants.MESH_PROTOCOL_VERSION)
            .put(name.size.toByte())
            .put(name)
            .array()

        val decoded = BleDiscoveryCodec.decode(payload, localNodeId) as BleDiscoveryCodec.DecodeResult.Parsed

        assertEquals("abcdefgh", decoded.discovery.deviceName)
        assertEquals("HMR-$nodeId", decoded.discovery.nodeId)
    }

    @Test
    fun v3PayloadParsesAsLegacyMeshProtocol() {
        val nodeId = UUID.randomUUID()
        val payload = ByteBuffer.allocate(BleConstants.LEGACY_DISCOVERY_BASE_BYTES + 1 + 2)
            .put(3)
            .putLong(nodeId.mostSignificantBits)
            .putLong(nodeId.leastSignificantBits)
            .put(BleConstants.DEVICE_TYPE_PHONE)
            .put(0x03)
            .put(2)
            .put("ab".toByteArray())
            .array()

        val decoded = BleDiscoveryCodec.decode(payload, localNodeId) as BleDiscoveryCodec.DecodeResult.Parsed

        assertEquals(BleConstants.LEGACY_MESH_PROTOCOL_VERSION.toInt(), decoded.discovery.meshProtocolVersion)
        assertTrue(decoded.discovery.canRelay && decoded.discovery.canStoreForward)
    }

    @Test
    fun rejectionsCarryAReason() {
        val own = BleDiscoveryCodec.encode(LocalIdentity(localNodeId, "Me"))

        assertEquals(
            BleDiscoveryCodec.DecodeResult.Rejected("SELF"),
            BleDiscoveryCodec.decode(own, localNodeId)
        )
        assertEquals(
            BleDiscoveryCodec.DecodeResult.Rejected("NO_MANUFACTURER_DATA"),
            BleDiscoveryCodec.decode(null, localNodeId)
        )
        assertTrue(
            (BleDiscoveryCodec.decode(own.copyOf(10), localNodeId) as BleDiscoveryCodec.DecodeResult.Rejected)
                .reason.startsWith("TOO_SHORT")
        )
    }
}
