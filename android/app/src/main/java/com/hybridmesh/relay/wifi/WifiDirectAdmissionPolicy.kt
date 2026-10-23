package com.hybridmesh.relay.wifi

internal data class WifiDirectPeerAdvertisement(
    val protocolVersion: String,
    val discoveryToken: String?,
    val groupOwnerAvailable: Boolean,
    val groupFormed: Boolean
)

/** Pure policy used by WifiDirectManager to avoid competing P2P invitations. */
internal object WifiDirectAdmissionPolicy {
    enum class CandidateAction {
        CONNECT,
        INCOMPATIBLE,
        LOCAL_GROUP_ALREADY_FORMED,
        ALREADY_CONNECTED,
        BUSY,
        BACKOFF,
        WAIT_FOR_GROUP_OWNER,
        WAIT_FOR_LEGACY_PEER
    }

    enum class SessionAction {
        ACCEPT_NEW,
        REPLACE_SAME_PEER,
        REJECT_CAPACITY,
        REJECT_ADDRESS_CONFLICT
    }

    fun discoveryAllowed(
        started: Boolean,
        groupFormed: Boolean,
        connectionAttemptInProgress: Boolean
    ): Boolean = started && !groupFormed && !connectionAttemptInProgress

    fun advertisesGroupOwner(record: Map<String, String>?): Boolean = record?.get("g") == "1"

    fun advertisesFormedGroup(record: Map<String, String>?): Boolean = record?.get("p") == "1"

    fun groupLossConfirmed(missingGroupSnapshots: Int, requiredConfirmations: Int): Boolean =
        requiredConfirmations > 0 && missingGroupSnapshots >= requiredConfirmations

    fun shouldScheduleGroupLossRecheck(
        missingGroupSnapshots: Int,
        requiredConfirmations: Int
    ): Boolean =
        requiredConfirmations > 1 &&
            missingGroupSnapshots in 1 until requiredConfirmations

    fun candidateAction(
        started: Boolean,
        protocolCompatible: Boolean,
        localGroupFormed: Boolean,
        remoteAdvertisesGroupOwner: Boolean,
        remoteGroupFormed: Boolean,
        localToken: String,
        remoteToken: String?,
        alreadyMember: Boolean,
        sessionAlreadyEstablished: Boolean,
        anotherAttemptInProgress: Boolean,
        retryAfterMs: Long,
        nowMs: Long
    ): CandidateAction {
        if (!started || !protocolCompatible) return CandidateAction.INCOMPATIBLE
        if (localGroupFormed) return CandidateAction.LOCAL_GROUP_ALREADY_FORMED
        if (alreadyMember || sessionAlreadyEstablished) return CandidateAction.ALREADY_CONNECTED
        if (anotherAttemptInProgress) return CandidateAction.BUSY
        if (nowMs < retryAfterMs) return CandidateAction.BACKOFF
        // A joining phone must invite an already-formed group owner regardless of
        // the ordinary token election: the owner is no longer discovering peers.
        if (remoteAdvertisesGroupOwner) return CandidateAction.CONNECT
        if (remoteGroupFormed) return CandidateAction.WAIT_FOR_GROUP_OWNER
        if (remoteToken.isNullOrBlank()) return CandidateAction.WAIT_FOR_LEGACY_PEER
        return if (localToken < remoteToken) CandidateAction.CONNECT
        else CandidateAction.WAIT_FOR_LEGACY_PEER
    }

    fun retryDelayMs(
        consecutiveFailures: Int,
        baseDelayMs: Long = 1_000L,
        maxDelayMs: Long = 30_000L
    ): Long {
        require(baseDelayMs > 0L && maxDelayMs >= baseDelayMs)
        if (consecutiveFailures <= 0) return 0L
        val shift = (consecutiveFailures - 1).coerceAtMost(30)
        val multiplier = 1L shl shift
        return if (baseDelayMs > maxDelayMs / multiplier) maxDelayMs
        else (baseDelayMs * multiplier).coerceAtMost(maxDelayMs)
    }

    fun discoveryRetryDelayMs(retryNumber: Int, baseDelayMs: Long, maxDelayMs: Long): Long =
        retryDelayMs(retryNumber, baseDelayMs, maxDelayMs)

    fun sessionAction(
        sessionExists: Boolean,
        existingAddress: String?,
        incomingAddress: String,
        sessionCount: Int,
        maxSessions: Int
    ): SessionAction = when {
        sessionExists && existingAddress.equals(incomingAddress, ignoreCase = true) ->
            SessionAction.REPLACE_SAME_PEER
        sessionExists -> SessionAction.REJECT_ADDRESS_CONFLICT
        sessionCount >= maxSessions -> SessionAction.REJECT_CAPACITY
        else -> SessionAction.ACCEPT_NEW
    }
}
