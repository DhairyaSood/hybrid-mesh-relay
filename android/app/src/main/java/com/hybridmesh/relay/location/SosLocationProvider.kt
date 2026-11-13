package com.hybridmesh.relay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.hybridmesh.relay.permissions.LocationPermissionLevel
import com.hybridmesh.relay.permissions.PermissionManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

data class SosFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    val provider: String
)

/**
 * Outcome of an SOS location lookup. [fix] is null when unavailable, with [reason] explaining why.
 * [clockSkewMs] is GPS time minus system clock, set only for a fresh GPS fix.
 */
data class SosLocationResult(
    val fix: SosFix?,
    val ageMs: Long?,
    val reason: String?,
    val clockSkewMs: Long? = null
) {
    val clockSkewSuspect: Boolean
        get() = clockSkewMs != null && abs(clockSkewMs) > SosLocationProvider.CLOCK_SKEW_WARN_MS
}

/** Thin seam over the platform location APIs so the lookup policy is unit-testable. */
interface SosLocationSource {
    fun permissionGranted(): Boolean
    fun locationEnabled(): Boolean
    suspend fun freshFix(): SosFix?
    fun lastKnownFixes(): List<SosFix>
}

class SosLocationProvider(
    private val source: SosLocationSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshTimeoutMs: Long = FRESH_TIMEOUT_MS
) {
    suspend fun locate(): SosLocationResult {
        if (!source.permissionGranted()) return unavailable(REASON_PERMISSION)
        if (!source.locationEnabled()) return unavailable(REASON_LOCATION_OFF)

        val fresh = withTimeoutOrNull(freshTimeoutMs) { source.freshFix() }
        if (fresh != null) {
            val skew = if (fresh.provider == AndroidLocationManager.GPS_PROVIDER) fresh.timeMillis - clock() else null
            return SosLocationResult(fresh, 0L, null, skew)
        }
        val stale = source.lastKnownFixes().maxByOrNull { it.timeMillis }
            ?: return unavailable(REASON_NO_FIX)
        return SosLocationResult(stale, (clock() - stale.timeMillis).coerceAtLeast(0L), null)
    }

    private fun unavailable(reason: String) = SosLocationResult(null, null, reason)

    companion object {
        const val FRESH_TIMEOUT_MS = 8_000L
        const val CLOCK_SKEW_WARN_MS = 60_000L
        const val REASON_PERMISSION = "permission denied"
        const val REASON_LOCATION_OFF = "location off"
        const val REASON_NO_FIX = "no fix"

        fun create(context: Context) = SosLocationProvider(AndroidSosLocationSource(context))
    }
}

class AndroidSosLocationSource(context: Context) : SosLocationSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager
    private val providers = listOf(
        AndroidLocationManager.GPS_PROVIDER,
        AndroidLocationManager.NETWORK_PROVIDER,
        AndroidLocationManager.PASSIVE_PROVIDER
    )

    override fun permissionGranted(): Boolean {
        val level = PermissionManager.location(appContext).level
        return level == LocationPermissionLevel.PRECISE || level == LocationPermissionLevel.APPROXIMATE
    }

    override fun locationEnabled(): Boolean =
        manager?.let { runCatching { LocationManagerCompat.isLocationEnabled(it) }.getOrDefault(false) } == true

    @SuppressLint("MissingPermission")
    override suspend fun freshFix(): SosFix? {
        val lm = manager ?: return null
        val active = listOf(AndroidLocationManager.GPS_PROVIDER, AndroidLocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (active.isEmpty()) return null
        return suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location.toFix())
                }
                @Deprecated("Deprecated by platform")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            continuation.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            val registered = active.count { provider ->
                runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }.isSuccess
            }
            if (registered == 0 && continuation.isActive) continuation.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun lastKnownFixes(): List<SosFix> {
        val lm = manager ?: return emptyList()
        return providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.toFix()
        }
    }

    private fun Location.toFix() = SosFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        timeMillis = time,
        provider = provider ?: ""
    )
}
