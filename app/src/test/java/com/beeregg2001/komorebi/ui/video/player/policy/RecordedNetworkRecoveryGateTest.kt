package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedNetworkRecoveryGateTest {
    @Test
    fun offlineFailure_waits_andOnlineEdgeRetriesExactlyOnce() {
        val gate = RecordedNetworkRecoveryGate(initiallyAvailable = true)

        assertEquals(RecordedNetworkRecoveryDecision.None, gate.onNetworkChanged(false))
        assertEquals(
            RecordedNetworkRecoveryDecision.WaitForNetwork,
            gate.onFailure(RecordedNetworkRetry.RenewStreamSession, networkAvailable = false),
        )
        assertEquals(
            RecordedNetworkRecoveryDecision.Retry(RecordedNetworkRetry.RenewStreamSession),
            gate.onNetworkChanged(true),
        )
        assertEquals(RecordedNetworkRecoveryDecision.None, gate.onNetworkChanged(true))
    }

    @Test
    fun repeatedOfflineErrors_coalesceWithoutConsumingAnOnlineAttempt() {
        val gate = RecordedNetworkRecoveryGate(initiallyAvailable = false)

        repeat(3) {
            assertEquals(
                RecordedNetworkRecoveryDecision.WaitForNetwork,
                gate.onFailure(RecordedNetworkRetry.RepreparePlayer, networkAvailable = false),
            )
        }
        assertEquals(
            RecordedNetworkRecoveryDecision.Retry(RecordedNetworkRetry.RepreparePlayer),
            gate.onNetworkChanged(true),
        )
    }

    @Test
    fun initialUrlRetry_takesPriorityOverLaterPlayerErrors() {
        val gate = RecordedNetworkRecoveryGate(initiallyAvailable = false)

        gate.onFailure(RecordedNetworkRetry.RepreparePlayer, networkAvailable = false)
        gate.onFailure(RecordedNetworkRetry.ResolveInitialUrl, networkAvailable = false)

        assertEquals(
            RecordedNetworkRecoveryDecision.Retry(RecordedNetworkRetry.ResolveInitialUrl),
            gate.onNetworkChanged(true),
        )
    }

    @Test
    fun onlineFailure_usesNormalPolicyImmediately() {
        val gate = RecordedNetworkRecoveryGate(initiallyAvailable = true)
        assertEquals(
            RecordedNetworkRecoveryDecision.ContinueOnline,
            gate.onFailure(RecordedNetworkRetry.RenewStreamSession, networkAvailable = true),
        )
    }

    @Test
    fun callbackRace_doesNotReleasePendingRetryOutsideNetworkEvent() {
        val gate = RecordedNetworkRecoveryGate(initiallyAvailable = true)
        gate.onFailure(RecordedNetworkRetry.RenewStreamSession, networkAvailable = false)

        assertEquals(
            RecordedNetworkRecoveryDecision.WaitForNetwork,
            gate.onFailure(RecordedNetworkRetry.RenewStreamSession, networkAvailable = true),
        )
        assertEquals(
            RecordedNetworkRecoveryDecision.Retry(RecordedNetworkRetry.RenewStreamSession),
            gate.onNetworkChanged(true),
        )
    }
}
