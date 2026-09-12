package com.hybridmesh.relay.data

import android.content.Context
import android.os.Build
import com.hybridmesh.relay.model.LocalIdentity
import java.util.UUID

class IdentityStore(
    context: Context
) {

    private val preferences = context.getSharedPreferences(
        "hybrid_mesh_identity",
        Context.MODE_PRIVATE
    )

    fun getIdentity(): LocalIdentity {

        val nodeId = preferences.getString(
            KEY_NODE_ID,
            null
        )

        val deviceName = preferences.getString(
            KEY_DEVICE_NAME,
            null
        )

        if (
            nodeId != null &&
            deviceName != null
        ) {
            return LocalIdentity(
                nodeId = nodeId,
                deviceName = deviceName
            )
        }

        val identity = LocalIdentity(
            nodeId = generateNodeId(),
            deviceName = defaultDeviceName()
        )

        saveIdentity(identity)

        return identity
    }

    fun updateDeviceName(
        deviceName: String
    ): LocalIdentity {

        val currentIdentity = getIdentity()

        val cleanedName = deviceName
            .trim()
            .ifBlank {
                currentIdentity.deviceName
            }

        val updatedIdentity = currentIdentity.copy(
            deviceName = cleanedName
        )

        saveIdentity(updatedIdentity)

        return updatedIdentity
    }

    private fun saveIdentity(
        identity: LocalIdentity
    ) {
        preferences.edit()
            .putString(
                KEY_NODE_ID,
                identity.nodeId
            )
            .putString(
                KEY_DEVICE_NAME,
                identity.deviceName
            )
            .apply()
    }

    private fun generateNodeId(): String {
        val shortId = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
            .uppercase()

        return "HM-$shortId"
    }

    private fun defaultDeviceName(): String {

        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar {
                it.uppercase()
            }

        val model = Build.MODEL

        return if (
            model.startsWith(
                manufacturer,
                ignoreCase = true
            )
        ) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    companion object {
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}