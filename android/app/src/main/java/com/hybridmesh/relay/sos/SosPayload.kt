package com.hybridmesh.relay.sos

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A parsed SOS text message. */
sealed interface SosMessage {
    data class Alert(
        val latitude: Double?,
        val longitude: Double?,
        val accuracyMeters: Int?,
        val fixAgeSeconds: Long?,
        val noLocationReason: String?,
        val utcTime: String,
        val localTime: String
    ) : SosMessage {
        val hasLocation: Boolean get() = latitude != null && longitude != null
    }

    data class Cancelled(val utcTime: String, val localTime: String) : SosMessage
}

/**
 * One-line, human-readable SOS wire format carried as ordinary message text.
 *
 *   SOS v1 | 12.971600,77.594600 | ±8 m, fix 14 s old | 2026-11-13T09:15:02Z | 14:45 IST | https://maps.google.com/?q=12.971600,77.594600
 *   SOS v1 | NO LOCATION (permission denied) | – | 2026-11-13T09:15:02Z | 14:45 IST
 *   SOS v1 CANCELLED | 2026-11-13T09:16:40Z | 14:46 IST
 */
object SosPayload {
    const val PREFIX = "SOS v"
    private const val VERSION = 1
    private const val SEPARATOR = " | "
    private const val NO_LOCATION = "NO LOCATION"
    private const val CANCELLED = "CANCELLED"

    private val utcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val header = Regex("""SOS v(\d+)(?: (\S+))?""")
    private val accuracyRegex = Regex("""±(\d+) m""")
    private val fixAgeRegex = Regex("""fix (\d+) s old""")
    private val reasonRegex = Regex("""NO LOCATION(?: \((.*)\))?""")

    /**
     * SOS messages (prefix "SOS v") must be EXCLUDED from any ML classification / advisory chip.
     * Any future classifier integration must check SosPayload.isSos(text) first and skip.
     */
    fun isSos(text: String): Boolean = text.startsWith(PREFIX)

    fun format(
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
        fixAgeMs: Long?,
        noLocationReason: String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val head = "SOS v$VERSION"
        val times = timestamps(nowMs, zone)
        if (latitude == null || longitude == null) {
            val reason = noLocationReason?.replace('|', '/')?.takeIf { it.isNotBlank() } ?: "unknown"
            return listOf(head, "$NO_LOCATION ($reason)", "–", times.first, times.second).joinToString(SEPARATOR)
        }
        val coords = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val accuracy = accuracyMeters?.let { "±${Math.round(it)} m" } ?: "accuracy unknown"
        val detail = if (fixAgeMs == null) accuracy else "$accuracy, fix ${fixAgeMs.coerceAtLeast(0L) / 1000} s old"
        return listOf(head, coords, detail, times.first, times.second, "https://maps.google.com/?q=$coords")
            .joinToString(SEPARATOR)
    }

    fun formatCancelled(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val times = timestamps(nowMs, zone)
        return listOf("SOS v$VERSION $CANCELLED", times.first, times.second).joinToString(SEPARATOR)
    }

    /** Lenient: tolerates extra trailing fields and later versions. Returns null for non-SOS text. */
    fun parse(text: String): SosMessage? {
        if (!isSos(text)) return null
        val parts = text.trim().split(SEPARATOR)
        val match = header.matchEntire(parts[0].trim()) ?: return null
        if (match.groupValues[2] == CANCELLED) {
            return SosMessage.Cancelled(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        }
        if (match.groupValues[2].isNotEmpty()) return null
        val locationField = parts.getOrElse(1) { "" }.trim()
        val detail = parts.getOrElse(2) { "" }
        val utc = parts.getOrElse(3) { "" }
        val local = parts.getOrElse(4) { "" }
        val coords = locationField.split(',').takeIf { it.size == 2 }
            ?.let { (lat, lon) -> lat.trim().toDoubleOrNull() to lon.trim().toDoubleOrNull() }
        val lat = coords?.first?.takeIf { it in -90.0..90.0 }
        val lon = coords?.second?.takeIf { it in -180.0..180.0 }
        if (lat == null || lon == null) {
            val reason = reasonRegex.matchEntire(locationField)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            return SosMessage.Alert(null, null, null, null, reason ?: locationField.ifEmpty { null }, utc, local)
        }
        return SosMessage.Alert(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracyRegex.find(detail)?.groupValues?.get(1)?.toIntOrNull(),
            fixAgeSeconds = fixAgeRegex.find(detail)?.groupValues?.get(1)?.toLongOrNull(),
            noLocationReason = null,
            utcTime = utc,
            localTime = local
        )
    }

    private fun timestamps(nowMs: Long, zone: ZoneId): Pair<String, String> {
        val instant = Instant.ofEpochMilli(nowMs)
        val local = DateTimeFormatter.ofPattern("HH:mm z", Locale.US).withZone(zone).format(instant)
        return utcFormatter.format(instant) to local
    }
}
