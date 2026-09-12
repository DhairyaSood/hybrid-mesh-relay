package com.hybridmesh.relay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hybridmesh.relay.data.IdentityStore
import com.hybridmesh.relay.model.LocalIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProfileViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val identityStore =
        IdentityStore(application)

    private val _identity =
        MutableStateFlow(
            identityStore.getIdentity()
        )

    val identity: StateFlow<LocalIdentity> =
        _identity

    val appVersion: String by lazy {
        try {
            application.packageManager
                .getPackageInfo(
                    application.packageName,
                    0
                )
                .versionName
                ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun updateDeviceName(
        deviceName: String
    ) {
        _identity.value =
            identityStore.updateDeviceName(
                deviceName
            )
    }
}