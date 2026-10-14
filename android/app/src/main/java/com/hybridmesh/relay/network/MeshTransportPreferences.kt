package com.hybridmesh.relay.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MeshTransportSelection(
    val bleEnabled: Boolean = true,
    val wifiDirectEnabled: Boolean = true
) {
    val anyEnabled: Boolean get() = bleEnabled || wifiDirectEnabled
}

/** Persisted, app-level bearer selection. This never changes Android radio settings. */
class MeshTransportPreferences private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _selection = MutableStateFlow(readSelection())
    val selection: StateFlow<MeshTransportSelection> = _selection.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _selection.value = readSelection()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setBleEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BLE_ENABLED, enabled).apply()
        _selection.value = readSelection()
    }

    fun setWifiDirectEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_DIRECT_ENABLED, enabled).apply()
        _selection.value = readSelection()
    }

    private fun readSelection() = MeshTransportSelection(
        bleEnabled = preferences.getBoolean(KEY_BLE_ENABLED, true),
        wifiDirectEnabled = preferences.getBoolean(KEY_WIFI_DIRECT_ENABLED, true)
    )

    companion object {
        private const val PREFERENCES_NAME = "neyra_mesh_transport_preferences"
        private const val KEY_BLE_ENABLED = "ble_enabled"
        private const val KEY_WIFI_DIRECT_ENABLED = "wifi_direct_enabled"

        @Volatile private var instance: MeshTransportPreferences? = null

        fun getInstance(context: Context): MeshTransportPreferences =
            instance ?: synchronized(this) {
                instance ?: MeshTransportPreferences(context.applicationContext).also { instance = it }
            }
    }
}
