package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.model.LocalIdentity
import kotlinx.coroutines.flow.StateFlow

class ProfileViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val identityStore =
        IdentityStore.getInstance(application)

    val identity: StateFlow<LocalIdentity> =
        identityStore.identity

    val appVersion: String by lazy {
        runCatching {
            application.packageManager
                .getPackageInfo(
                    application.packageName,
                    0
                )
                .versionName
                ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    fun updateDeviceName(
        deviceName: String
    ) {
        identityStore.updateDeviceName(deviceName)
    }
}
