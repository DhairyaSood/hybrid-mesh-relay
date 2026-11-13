package com.hybridmesh.relay.sos

import com.hybridmesh.relay.messaging.mesh.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class SosPayloadTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-11-13T09:15:02Z
    private val now = 1_794_561_302_000L

    private fun alert() = SosPayload.format(12.9716, 77.5946, 8f, 14_000L, null, now, zone)

    @Test
    fun formatsDocumentedLine() {
        assertEquals(
            "SOS v1 | 12.971600,77.594600 | ±8 m, fix 14 s old | 2026-11-13T09:15:02Z | 14:45 IST | " +
                "https://maps.google.com/?q=12.971600,77.594600",
            alert()
        )
    }

    @Test
    fun roundTrip() {
        val parsed = SosPayload.parse(alert()) as SosMessage.Alert
        assertEquals(12.9716, parsed.latitude!!, 1e-9)
        assertEquals(77.5946, parsed.longitude!!, 1e-9)
        assertEquals(8, parsed.accuracyMeters)
        assertEquals(14L, parsed.fixAgeSeconds)
        assertEquals("2026-11-13T09:15:02Z", parsed.utcTime)
        assertEquals("14:45 IST", parsed.localTime)
        assertTrue(parsed.hasLocation)
    }

    @Test
    fun noLocation() {
        val text = SosPayload.format(null, null, null, null, "permission denied", now, zone)
        assertEquals("SOS v1 | NO LOCATION (permission denied) | – | 2026-11-13T09:15:02Z | 14:45 IST", text)
        val parsed = SosPayload.parse(text) as SosMessage.Alert
        assertFalse(parsed.hasLocation)
        assertEquals("permission denied", parsed.noLocationReason)
        assertNull(parsed.accuracyMeters)
        assertEquals("14:45 IST", parsed.localTime)
    }

    @Test
    fun cancelled() {
        val text = SosPayload.formatCancelled(now, zone)
        assertEquals("SOS v1 CANCELLED | 2026-11-13T09:15:02Z | 14:45 IST", text)
        assertEquals(SosMessage.Cancelled("2026-11-13T09:15:02Z", "14:45 IST"), SosPayload.parse(text))
    }

    @Test
    fun rejectsNormalText() {
        assertNull(SosPayload.parse("hello"))
        assertNull(SosPayload.parse("please send SOS v1 | 1,2"))
        assertFalse(SosPayload.isSos("sos v1"))
        assertTrue(SosPayload.isSos(alert()))
    }

    @Test
    fun parsesFutureVersionWithExtraFields() {
        val parsed = SosPayload.parse(
            "SOS v2 | 1.500000,2.500000 | ±3 m, fix 1 s old | 2027-01-01T00:00:00Z | 05:30 IST | https://x | battery 40% | extra"
        ) as SosMessage.Alert
        assertEquals(1.5, parsed.latitude!!, 1e-9)
        assertEquals(2.5, parsed.longitude!!, 1e-9)
        assertEquals(3, parsed.accuracyMeters)
    }

    @Test
    fun fitsInMeshPayload() {
        assertTrue(alert().toByteArray(Charsets.UTF_8).size < MeshPacketCodec.MAX_TEXT_BYTES)
    }

    @Test
    fun localeIndependent() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale("ar", "EG"), Locale("hi", "IN"))) {
                Locale.setDefault(locale)
                assertEquals(alert(), "SOS v1 | 12.971600,77.594600 | ±8 m, fix 14 s old | 2026-11-13T09:15:02Z | 14:45 IST | " +
                    "https://maps.google.com/?q=12.971600,77.594600")
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
