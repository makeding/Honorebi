package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNetworkRecoveryGateTest {
    @Test fun offlineFailuresWaitAndDoNotStartRecoveryUntilTheOnlineEdge() {
        val gate = LiveNetworkRecoveryGate(initiallyAvailable = true)

        assertEquals(LiveNetworkRecoveryDecision.None, gate.onNetworkChanged(false))
        assertEquals(LiveNetworkRecoveryDecision.WaitForNetwork, gate.onRecoveryNeeded(false))
        assertEquals(LiveNetworkRecoveryDecision.WaitForNetwork, gate.onRecoveryNeeded(false))
        assertEquals(LiveNetworkRecoveryDecision.RecoverNow, gate.onNetworkChanged(true))
        assertEquals(LiveNetworkRecoveryDecision.None, gate.onNetworkChanged(true))
    }

    @Test fun anOnlineFailureUsesTheNormalSingleRecoveryPath() {
        val gate = LiveNetworkRecoveryGate(initiallyAvailable = true)

        assertEquals(LiveNetworkRecoveryDecision.RecoverNow, gate.onRecoveryNeeded(true))
        assertEquals(LiveNetworkRecoveryDecision.None, gate.onNetworkChanged(true))
    }

    @Test fun aFreshPlaybackClearsAnOldParkedRetry() {
        val gate = LiveNetworkRecoveryGate(initiallyAvailable = false)

        assertEquals(LiveNetworkRecoveryDecision.WaitForNetwork, gate.onRecoveryNeeded(false))
        gate.onPlaybackStarted()
        assertEquals(LiveNetworkRecoveryDecision.None, gate.onNetworkChanged(true))
    }
}
