package com.hybridmesh.relay

import com.hybridmesh.relay.location.LocationActions
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.network.percentile
import com.hybridmesh.relay.network.NetworkDiagnosticEvent
import com.hybridmesh.relay.network.NetworkDiagnosticsSnapshot
import com.hybridmesh.relay.network.NetworkEventKind
import com.hybridmesh.relay.network.connectionQuality
import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import com.hybridmesh.relay.messaging.data.MessageRetentionPolicy
import com.hybridmesh.relay.wifi.ProbeFailurePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFeatureTest {
    @Test
    fun wifiSessionInvalidationRequiresConsecutiveProbeFailures() {
        val policy = ProbeFailurePolicy(failureThreshold = 2)

        assertEquals(true, policy.isHealthy)
        assertEquals(false, policy.record(false))
        assertEquals(false, policy.isHealthy)
        assertEquals(false, policy.record(true))
        assertEquals(true, policy.isHealthy)
        assertEquals(false, policy.record(false))
        assertEquals(true, policy.record(false))
        assertEquals(false, policy.isHealthy)
    }

    @Test
    fun meshTextLimitIsMeasuredInUtf8Bytes() {
        val ascii = "a".repeat(MeshPacketCodec.MAX_TEXT_BYTES)
        val multibyte = "é".repeat(MeshPacketCodec.MAX_TEXT_BYTES / 2 + 1)

        assertEquals(MeshPacketCodec.MAX_TEXT_BYTES, ascii.toByteArray(Charsets.UTF_8).size)
        assertEquals(MeshPacketCodec.MAX_TEXT_BYTES + 2, multibyte.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun remoteMessageAdmissionRejectsGlobalSenderAndByteLimitOverflows() {
        assertEquals(true, MessageRetentionPolicy.allowsIncomingMessage(1, 1, 10L, 10))
        assertEquals(false, MessageRetentionPolicy.allowsIncomingMessage(MessageRetentionPolicy.MAX_REMOTE_MESSAGES, 1, 10L, 10))
        assertEquals(false, MessageRetentionPolicy.allowsIncomingMessage(1, MessageRetentionPolicy.MAX_REMOTE_MESSAGES_PER_SENDER, 10L, 10))
        assertEquals(false, MessageRetentionPolicy.allowsIncomingMessage(1, 1, MessageRetentionPolicy.MAX_REMOTE_MESSAGE_BYTES, 1))
    }

    @Test
    fun remoteAttachmentAdmissionBoundsCountAndAggregateBytes() {
        assertEquals(true, MessageRetentionPolicy.allowsIncomingAttachment(1, 10L, 10L))
        assertEquals(false, MessageRetentionPolicy.allowsIncomingAttachment(MessageRetentionPolicy.MAX_REMOTE_ATTACHMENTS, 10L, 10L))
        assertEquals(false, MessageRetentionPolicy.allowsIncomingAttachment(1, MessageRetentionPolicy.MAX_REMOTE_ATTACHMENT_BYTES, 1L))
    }

    @Test
    fun mapsUriUsesExactCoordinates() {
        val url = LocationActions.mapsUrl(
            LocationPayload(28.6139, 77.2090, null, 0L)
        )

        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=28.6139,77.209",
            url
        )
    }

    @Test
    fun mapsUriRejectsInvalidCoordinates() {
        assertNull(LocationActions.mapsUrl(LocationPayload(91.0, 0.0, null, 0L)))
        assertNull(LocationActions.mapsUrl(LocationPayload(0.0, -181.0, null, 0L)))
    }

    @Test
    fun percentileUsesSortedMeasuredSamples() {
        assertEquals(20L, percentile(listOf(30L, 10L, 20L), 0.5))
        assertEquals(30L, percentile(listOf(30L, 10L, 20L), 0.95))
        assertNull(percentile(emptyList(), 0.5))
    }

    @Test
    fun wifiPeerHealthUsesRecentMeasuredSamples() {
        val health = NetworkDiagnosticsSnapshot(
            events = listOf(
                NetworkDiagnosticEvent(1, 1_000L, NetworkEventKind.REQUEST_SUCCEEDED, "mesh.send", transport = "WIFI_DIRECT", peerNodeId = "peer", durationMs = 40L),
                NetworkDiagnosticEvent(2, 2_000L, NetworkEventKind.REQUEST_FAILED, "mesh.send", transport = "WIFI_DIRECT", peerNodeId = "peer", durationMs = 500L)
            )
        ).peerHealth("PEER", "wifi_direct", now = 2_000L)

        assertEquals(50.0, health?.successRatePercent ?: 0.0, 0.0)
        assertEquals(40L, health?.medianLatencyMs)
        assertEquals("POOR", health?.label)
    }

    @Test
    fun combinedConnectionQualityUsesOneConservativeLabel() {
        val health = NetworkDiagnosticsSnapshot(
            events = listOf(
                NetworkDiagnosticEvent(
                    1,
                    1_000L,
                    NetworkEventKind.HEALTH_PROBE_SUCCEEDED,
                    "wifi.health_probe",
                    transport = "WIFI_DIRECT",
                    peerNodeId = "peer",
                    durationMs = 40L
                )
            )
        ).peerHealth("peer", "WIFI_DIRECT", now = 1_000L)

        val quality = connectionQuality(bleRssi = -80, wifiHealth = health)

        assertEquals("POOR", quality?.label)
        assertEquals("BLE -80 dBm · Wi-Fi 40 ms", quality?.detail)
    }
}
