package com.hybridmesh.relay.location

import java.util.Locale

/** Stable, dependency-free structured location payload carried by the same message pipeline. */
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestamp: Long
) {
    fun encode(): String = buildString {
        append(PREFIX)
        append("lat=").append(latitude).append(';')
        append("lng=").append(longitude).append(';')
        append("acc=").append(accuracyMeters ?: -1f).append(';')
        append("ts=").append(timestamp)
    }

    companion object {
        private const val PREFIX = "NEYRA-LOC|"

        fun decode(content: String): LocationPayload? {
            if (!content.startsWith(PREFIX)) return null
            val values = content.removePrefix(PREFIX)
                .split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
                }
                .toMap()
            return runCatching {
                val accuracy = values["acc"]?.toFloatOrNull()?.takeIf { it >= 0f }
                LocationPayload(
                    latitude = values.getValue("lat").toDouble(),
                    longitude = values.getValue("lng").toDouble(),
                    accuracyMeters = accuracy,
                    timestamp = values.getValue("ts").toLong()
                )
            }.getOrNull()
        }

        fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)
    }
}
