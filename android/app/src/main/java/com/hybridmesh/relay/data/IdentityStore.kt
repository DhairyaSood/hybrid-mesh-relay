package com.hybridmesh.relay.data

import android.content.Context
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
        preferences.getString(KEY_DEVICE_NAME, null)?.let(NicknamePolicy::isValid) == true

    fun updateDeviceName(name: String): LocalIdentity {
        val cleaned = NicknamePolicy.clean(name)
        require(NicknamePolicy.isValid(cleaned)) {
            NicknamePolicy.errorMessage(cleaned) ?: "Invalid username"
        }

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
        val name = storedName
            ?.let(NicknamePolicy::clean)
            ?.takeIf(NicknamePolicy::isValid)
            ?: ""

        // A fresh installation deliberately has no default username. The user must
        // explicitly configure one before the mesh advertises this identity.
        return LocalIdentity(
            nodeId = nodeId,
            deviceName = name,
            deviceType = NodeType.PHONE
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "hybrid_mesh_identity"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DEVICE_NAME = "device_name"
        const val MAX_DISPLAY_NAME_CHARS = NicknamePolicy.MAX_LENGTH

        @Volatile private var INSTANCE: IdentityStore? = null

        fun getInstance(context: Context): IdentityStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: IdentityStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
