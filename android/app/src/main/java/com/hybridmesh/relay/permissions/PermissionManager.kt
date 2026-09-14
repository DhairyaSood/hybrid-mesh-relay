package com.hybridmesh.relay.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BlePermissionState(
    val scanGranted: Boolean,
    val advertiseGranted: Boolean,
    val connectGranted: Boolean
) {
    val allGranted: Boolean
        get() = scanGranted && advertiseGranted && connectGranted
}

enum class LocationPermissionLevel { NOT_REQUESTED, APPROXIMATE, PRECISE, DENIED }
data class LocationPermissionState(val level: LocationPermissionLevel)

enum class NotificationPermissionLevel { NOT_REQUESTED, GRANTED, DENIED }
data class NotificationPermissionState(
    val level: NotificationPermissionLevel,
    val requestable: Boolean,
    val previouslyGranted: Boolean
) {
    val granted: Boolean
        get() = level == NotificationPermissionLevel.GRANTED
}

/** Vendor-neutral permission source of truth. Request history is persisted only to distinguish first use from revocation/denial. */
object PermissionManager {
    private const val PREFS = "neyra_permission_state"
    private const val KEY_LOCATION_REQUESTED = "location_requested"
    private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
    private const val KEY_NOTIFICATION_PREVIOUSLY_GRANTED = "notification_previously_granted"

    fun ble(context: Context): BlePermissionState {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlePermissionState(
                scanGranted = granted(appContext, Manifest.permission.BLUETOOTH_SCAN),
                advertiseGranted = granted(appContext, Manifest.permission.BLUETOOTH_ADVERTISE),
                connectGranted = granted(appContext, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            val legacy = granted(appContext, Manifest.permission.BLUETOOTH)
            BlePermissionState(
                scanGranted = legacy && granted(appContext, Manifest.permission.ACCESS_FINE_LOCATION),
                advertiseGranted = legacy,
                connectGranted = legacy
            )
        }
    }

    fun missingBlePermissions(context: Context): Array<String> {
        val appContext = context.applicationContext
        val state = ble(appContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                if (!state.scanGranted) add(Manifest.permission.BLUETOOTH_SCAN)
                if (!state.advertiseGranted) add(Manifest.permission.BLUETOOTH_ADVERTISE)
                if (!state.connectGranted) add(Manifest.permission.BLUETOOTH_CONNECT)
            }.toTypedArray()
        } else {
            buildList {
                if (!granted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }.toTypedArray()
        }
    }

    fun location(context: Context): LocationPermissionState {
        val appContext = context.applicationContext
        val fine = granted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> LocationPermissionState(LocationPermissionLevel.PRECISE)
            coarse -> LocationPermissionState(LocationPermissionLevel.APPROXIMATE)
            wasRequested(appContext, KEY_LOCATION_REQUESTED) -> LocationPermissionState(LocationPermissionLevel.DENIED)
            else -> LocationPermissionState(LocationPermissionLevel.NOT_REQUESTED)
        }
    }

    fun locationPermissionsToRequest(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun markLocationRequestAttempted(context: Context) {
        prefs(context).edit().putBoolean(KEY_LOCATION_REQUESTED, true).apply()
    }

    fun notifications(context: Context): NotificationPermissionState {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationPermissionState(
                level = NotificationPermissionLevel.GRANTED,
                requestable = false,
                previouslyGranted = true
            )
        }
        val isGranted = granted(appContext, Manifest.permission.POST_NOTIFICATIONS)
        val requested = wasRequested(appContext, KEY_NOTIFICATION_REQUESTED)
        if (isGranted) {
            if (!prefs(appContext).getBoolean(KEY_NOTIFICATION_PREVIOUSLY_GRANTED, false)) {
                prefs(appContext).edit().putBoolean(KEY_NOTIFICATION_PREVIOUSLY_GRANTED, true).apply()
            }
        }
        return NotificationPermissionState(
            level = when {
                isGranted -> NotificationPermissionLevel.GRANTED
                !requested -> NotificationPermissionLevel.NOT_REQUESTED
                else -> NotificationPermissionLevel.DENIED
            },
            requestable = !isGranted,
            previouslyGranted = prefs(appContext).getBoolean(KEY_NOTIFICATION_PREVIOUSLY_GRANTED, false)
        )
    }

    fun notificationPermissionsToRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()

    fun markNotificationRequestCompleted(context: Context, granted: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true)
        if (granted) editor.putBoolean(KEY_NOTIFICATION_PREVIOUSLY_GRANTED, true)
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wasRequested(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context.applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED
}
