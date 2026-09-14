package com.hybridmesh.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hybridmesh.relay.permissions.PermissionManager

/** Best-effort reboot recovery. Android/OEM policies may still prevent a boot-time start. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        if (!com.hybridmesh.relay.data.IdentityStore.getInstance(appContext).isNicknameConfigured()) return
        if (!PermissionManager.ble(appContext).allGranted) return
        runCatching { HybridMeshService.start(appContext) }
    }
}
