package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRecoveryLifecycleGateTest {
    @Test fun stopQueuesRecoveryAndStartReleasesItExactlyOnce() {
        val gate = PlayerRecoveryLifecycleGate(initiallyForeground = true)
        val token = gate.beginRecovery()

        gate.onStop()
        assertSame(PlayerRecoveryResumeDecision.ResumeOnStart, gate.finishDelay(token, stillCurrent = true))
        assertTrue(gate.onStart())
        assertFalse(gate.onStart())
    }

    @Test fun stopAndStartBeforeDelayAllowsRecoveryInTheNewForeground() {
        val gate = PlayerRecoveryLifecycleGate(initiallyForeground = true)
        val token = gate.beginRecovery()

        gate.onStop()
        assertFalse(gate.onStart())
        assertSame(PlayerRecoveryResumeDecision.ResumeNow, gate.finishDelay(token, stillCurrent = true))
    }

    @Test fun retiredOrSupersededRuntimeCannotResume() {
        val gate = PlayerRecoveryLifecycleGate(initiallyForeground = true)
        val token = gate.beginRecovery()

        gate.retire()

        assertSame(PlayerRecoveryResumeDecision.Drop, gate.finishDelay(token, stillCurrent = true))
        assertFalse(gate.onStart())
    }
}
