package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPlaybackRecoveryPolicyTest {
    @Test fun automaticRecoveryStopsAtItsBudget() {
        val policy = SmbPlaybackRecoveryPolicy(maxAutomaticRetries = 2)

        assertSame(SmbPlaybackRecoveryDecision.Reprepare, policy.onPlayerError())
        assertSame(SmbPlaybackRecoveryDecision.Reprepare, policy.onPlayerError())
        assertSame(SmbPlaybackRecoveryDecision.ShowFailure, policy.onPlayerError())
        assertSame(SmbPlaybackRecoveryDecision.ShowFailure, policy.onPlayerError())
    }

    @Test fun manualRetryIsSingleFlightAndStartsOneFreshAutomaticBudget() {
        val policy = SmbPlaybackRecoveryPolicy(maxAutomaticRetries = 1)
        assertSame(SmbPlaybackRecoveryDecision.Reprepare, policy.onPlayerError())
        assertSame(SmbPlaybackRecoveryDecision.ShowFailure, policy.onPlayerError())

        assertTrue(policy.beginManualRetry())
        assertFalse(policy.beginManualRetry())
        assertSame(SmbPlaybackRecoveryDecision.Reprepare, policy.onPlayerError())
        assertSame(SmbPlaybackRecoveryDecision.ShowFailure, policy.onPlayerError())
    }

    @Test fun readyCompletesManualSingleFlightWithoutResettingAutomaticBudget() {
        val policy = SmbPlaybackRecoveryPolicy(maxAutomaticRetries = 1)
        assertTrue(policy.beginManualRetry())
        policy.onReady()
        assertTrue(policy.beginManualRetry())
        assertSame(SmbPlaybackRecoveryDecision.Reprepare, policy.onPlayerError())
        policy.onReady()
        assertSame(SmbPlaybackRecoveryDecision.ShowFailure, policy.onPlayerError())
    }
}
