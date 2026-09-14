package com.hybridmesh.relay.data

import android.content.Context
import android.os.Build
import com.hybridmesh.relay.model.LocalIdentity
import com.hybridmesh.relay.model.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-wide identity source of truth. */
class IdentityStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    private val _identity = MutableStateFlow(loadOrCreate())
    val identity: StateFlow<LocalIdentity> = _identity.asStateFlow()

    fun getIdentity(): LocalIdentity = _identity.value

    fun isNicknameConfigured(): Boolean =
        preferences.getString(KEY_DEVICE_NAME, null)?.trim()?.isNotBlank() == true

    fun updateDeviceName(name: String): LocalIdentity {
        val cleaned = name.trim().take(MAX_DISPLAY_NAME_CHARS)
        require(cleaned.isNotBlank()) { "Nickname cannot be blank" }

        val current = _identity.value
        if (current.deviceName == cleaned && isNicknameConfigured()) return current

        preferences.edit().putString(KEY_DEVICE_NAME, cleaned).apply()
        return current.copy(deviceName = cleaned).also { _identity.value = it }
    }

    private fun loadOrCreate(): LocalIdentity {
        val storedNodeId = preferences.getString(KEY_NODE_ID, null)
        val nodeId = storedNodeId
            ?.takeIf(NodeIdGenerator::isValid)
            ?: NodeIdGenerator.generate().also {
                preferences.edit().putString(KEY_NODE_ID, it).apply()
            }

        val storedName = preferences.getString(KEY_DEVICE_NAME, null)
        val name = storedName?.trim()?.take(MAX_DISPLAY_NAME_CHARS)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()

        // Existing installations already had an identity name; new installations do not.
        // The distinction is stored by the presence of KEY_DEVICE_NAME, not by the fallback model name.
        return LocalIdentity(
            nodeId = nodeId,
            deviceName = name,
            deviceType = NodeType.PHONE
        )
    }

    private fun defaultDeviceName(): String =
        Build.MODEL?.trim()?.takeIf { it.isNotBlank() } ?: "Neyra Device"

    companion object {
        private const val PREFERENCES_NAME = "hybrid_mesh_identity"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DEVICE_NAME = "device_name"
        const val MAX_DISPLAY_NAME_CHARS = 32

        @Volatile private var INSTANCE: IdentityStore? = null

        fun getInstance(context: Context): IdentityStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: IdentityStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
