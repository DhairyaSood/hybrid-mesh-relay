package com.hybridmesh.relay.data

import java.util.UUID

/**
 * Generates the permanent logical identity for one installation of the app.
 * The ID is deliberately large enough that decentralized accidental
 * collisions are practically negligible.
 */
object NodeIdGenerator {
    fun generate(): String = "HMR-${UUID.randomUUID()}"

    fun isValid(nodeId: String): Boolean {
        val value = nodeId.removePrefix("HMR-")
        return runCatching { UUID.fromString(value) }.isSuccess
    }
}
