package com.hybridmesh.relay.location

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.Activity
import android.net.Uri
import androidx.core.net.toUri

object LocationActions {
    fun mapsUri(payload: LocationPayload): Uri? {
        val url = mapsUrl(payload) ?: return null
        return url.toUri()
    }

    fun mapsUrl(payload: LocationPayload): String? {
        if (payload.latitude !in -90.0..90.0 || payload.longitude !in -180.0..180.0) return null
        return "https://www.google.com/maps/search/?api=1&query=" +
            "${payload.latitude},${payload.longitude}"
    }

    fun openInMaps(context: Context, payload: LocationPayload): Boolean {
        val uri = mapsUri(payload) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
