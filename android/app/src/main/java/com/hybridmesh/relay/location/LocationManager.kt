package com.hybridmesh.relay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.os.Looper
import com.hybridmesh.relay.permissions.LocationPermissionLevel
import com.hybridmesh.relay.permissions.PermissionManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMs: Long = 12_000L): Location? {
        val permission = PermissionManager.location(appContext).level
        if (permission != LocationPermissionLevel.PRECISE && permission != LocationPermissionLevel.APPROXIMATE) {
            return null
        }

        val locationManager = manager ?: return null
        val providers = listOf(
            AndroidLocationManager.GPS_PROVIDER,
            AndroidLocationManager.NETWORK_PROVIDER,
            AndroidLocationManager.PASSIVE_PROVIDER
        ).filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

        val recent = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (recent != null && System.currentTimeMillis() - recent.time <= MAX_LAST_KNOWN_AGE_MS) {
            return recent
        }

        val provider = providers.firstOrNull() ?: return recent
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                        runCatching { locationManager.removeUpdates(this) }
                    }
                    @Deprecated("Deprecated by platform")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
                runCatching {
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } ?: recent
    }

    companion object {
        private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60_000L
    }
}
