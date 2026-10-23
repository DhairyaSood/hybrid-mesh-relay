package com.hybridmesh.relay.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectAdmissionPolicyTest {
    @Test
    fun discoveryOnlyRunsBeforeGroupFormationAndOutsideAnAttempt() {
        assertTrue(WifiDirectAdmissionPolicy.discoveryAllowed(true, false, false))
        assertFalse(WifiDirectAdmissionPolicy.discoveryAllowed(false, false, false))
        assertFalse(WifiDirectAdmissionPolicy.discoveryAllowed(true, true, false))
        assertFalse(WifiDirectAdmissionPolicy.discoveryAllowed(true, false, true))
    }

    @Test
    fun groupTeardownRequiresRepeatedAuthoritativeEmptySnapshots() {
        assertFalse(WifiDirectAdmissionPolicy.groupLossConfirmed(0, 2))
        assertFalse(WifiDirectAdmissionPolicy.groupLossConfirmed(1, 2))
        assertTrue(WifiDirectAdmissionPolicy.groupLossConfirmed(2, 2))
    }

    @Test
    fun firstEmptySnapshotSchedulesRecheckButConfirmedLossDoesNot() {
        assertFalse(WifiDirectAdmissionPolicy.shouldScheduleGroupLossRecheck(0, 2))
        assertTrue(WifiDirectAdmissionPolicy.shouldScheduleGroupLossRecheck(1, 2))
        assertFalse(WifiDirectAdmissionPolicy.shouldScheduleGroupLossRecheck(2, 2))
    }

    @Test
    fun tokenElectionChoosesExactlyOneInitiatorForUngroupedPeers() {
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.CONNECT,
            action(localToken = "a", remoteToken = "b")
        )
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.WAIT_FOR_LEGACY_PEER,
            action(localToken = "b", remoteToken = "a")
        )
    }

    @Test
    fun joiningPhoneAlwaysInitiatesTowardAdvertisedGroupOwner() {
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.CONNECT,
            action(localToken = "z", remoteToken = "a", remoteGroupOwner = true)
        )
    }

    @Test
    fun groupOwnerAdvertisementIsExplicitAndDefaultsToNoGroup() {
        assertTrue(WifiDirectAdmissionPolicy.advertisesGroupOwner(mapOf("g" to "1")))
        assertFalse(WifiDirectAdmissionPolicy.advertisesGroupOwner(mapOf("g" to "0")))
        assertFalse(WifiDirectAdmissionPolicy.advertisesGroupOwner(emptyMap()))
        assertFalse(WifiDirectAdmissionPolicy.advertisesGroupOwner(null))
        assertTrue(WifiDirectAdmissionPolicy.advertisesFormedGroup(mapOf("p" to "1")))
        assertFalse(WifiDirectAdmissionPolicy.advertisesFormedGroup(mapOf("p" to "0")))
        assertFalse(WifiDirectAdmissionPolicy.advertisesFormedGroup(null))
    }

    @Test
    fun groupClientDoesNotCompeteWithTheActualGroupOwnerForNewJoiners() {
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.WAIT_FOR_GROUP_OWNER,
            action(localToken = "a", remoteToken = "z", remoteGroupFormed = true)
        )
    }

    @Test
    fun groupMembersDoNotStartCompetingP2pAttempts() {
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.LOCAL_GROUP_ALREADY_FORMED,
            action(localGroupFormed = true, remoteGroupOwner = true)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.ALREADY_CONNECTED,
            action(alreadyMember = true)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.BUSY,
            action(attemptInProgress = true)
        )
    }

    @Test
    fun incompatibleAndLegacyAdvertisementsAreHandledExplicitly() {
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.INCOMPATIBLE,
            action(protocolCompatible = false)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.WAIT_FOR_LEGACY_PEER,
            action(remoteToken = null)
        )
    }

    @Test
    fun candidateRetriesUseBoundedExponentialBackoff() {
        assertEquals(0L, WifiDirectAdmissionPolicy.retryDelayMs(0))
        assertEquals(1_000L, WifiDirectAdmissionPolicy.retryDelayMs(1))
        assertEquals(2_000L, WifiDirectAdmissionPolicy.retryDelayMs(2))
        assertEquals(4_000L, WifiDirectAdmissionPolicy.retryDelayMs(3))
        assertEquals(30_000L, WifiDirectAdmissionPolicy.retryDelayMs(20))
        assertEquals(
            WifiDirectAdmissionPolicy.CandidateAction.BACKOFF,
            action(retryAfterMs = 2_000L, nowMs = 1_999L)
        )
    }

    @Test
    fun discoveryFailureRetryUsesBoundedBackoff() {
        assertEquals(3_000L, WifiDirectAdmissionPolicy.discoveryRetryDelayMs(1, 3_000L, 30_000L))
        assertEquals(6_000L, WifiDirectAdmissionPolicy.discoveryRetryDelayMs(2, 3_000L, 30_000L))
        assertEquals(12_000L, WifiDirectAdmissionPolicy.discoveryRetryDelayMs(3, 3_000L, 30_000L))
        assertEquals(30_000L, WifiDirectAdmissionPolicy.discoveryRetryDelayMs(8, 3_000L, 30_000L))
    }

    @Test
    fun groupOwnerCanAdmitSeveralDistinctSessionsUpToLocalSafetyLimit() {
        assertEquals(
            WifiDirectAdmissionPolicy.SessionAction.ACCEPT_NEW,
            sessionAction(sessionCount = 0)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.SessionAction.ACCEPT_NEW,
            sessionAction(sessionCount = 1)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.SessionAction.REJECT_CAPACITY,
            sessionAction(sessionCount = 8)
        )
    }

    @Test
    fun duplicatePeerSocketReplacesOnlySameAddressAndRejectsIdentityHijack() {
        assertEquals(
            WifiDirectAdmissionPolicy.SessionAction.REPLACE_SAME_PEER,
            WifiDirectAdmissionPolicy.sessionAction(true, "192.168.49.2", "192.168.49.2", 1, 8)
        )
        assertEquals(
            WifiDirectAdmissionPolicy.SessionAction.REJECT_ADDRESS_CONFLICT,
            WifiDirectAdmissionPolicy.sessionAction(true, "192.168.49.2", "192.168.49.3", 1, 8)
        )
    }

    private fun action(
        protocolCompatible: Boolean = true,
        localGroupFormed: Boolean = false,
        remoteGroupOwner: Boolean = false,
        remoteGroupFormed: Boolean = false,
        localToken: String = "a",
        remoteToken: String? = "b",
        alreadyMember: Boolean = false,
        attemptInProgress: Boolean = false,
        retryAfterMs: Long = 0L,
        nowMs: Long = 1_000L
    ) = WifiDirectAdmissionPolicy.candidateAction(
        started = true,
        protocolCompatible = protocolCompatible,
        localGroupFormed = localGroupFormed,
        remoteAdvertisesGroupOwner = remoteGroupOwner,
        remoteGroupFormed = remoteGroupFormed,
        localToken = localToken,
        remoteToken = remoteToken,
        alreadyMember = alreadyMember,
        sessionAlreadyEstablished = false,
        anotherAttemptInProgress = attemptInProgress,
        retryAfterMs = retryAfterMs,
        nowMs = nowMs
    )

    private fun sessionAction(sessionCount: Int) = WifiDirectAdmissionPolicy.sessionAction(
        sessionExists = false,
        existingAddress = null,
        incomingAddress = "192.168.49.2",
        sessionCount = sessionCount,
        maxSessions = 8
    )
}
