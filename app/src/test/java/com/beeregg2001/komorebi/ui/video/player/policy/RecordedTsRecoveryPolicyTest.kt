package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.*
import org.junit.Test

class RecordedTsRecoveryPolicyTest {
    @Test fun stalledBufferWithDataNeedsTwelveSeconds() {
        val policy = RecordedTsRecoveryPolicy()
        assertFalse(policy.observe(0, 4818, 4839, 0, true, true, false))
        assertFalse(policy.observe(11_999, 4818, 4839, 2_000_000, true, true, false))
        assertTrue(policy.observe(12_000, 4818, 4839, 2_000_000, true, true, false))
    }

    @Test fun slowNetworkAndAdvancingBufferDoNotSkip() {
        val policy = RecordedTsRecoveryPolicy()
        assertFalse(policy.observe(0, 1000, 1100, 0, true, true, false))
        assertFalse(policy.observe(20_000, 1000, 1100, 200, true, true, false))
        assertFalse(policy.observe(21_000, 1000, 2100, 2_000_000, true, true, false))
        assertFalse(policy.observe(30_000, 1000, 2100, 4_000_000, true, true, false))
    }

    @Test fun stoppedLoadingWithNoPlayableBufferCanRecover() {
        val policy = RecordedTsRecoveryPolicy()
        policy.observe(0, 4818, 4839, 0, true, false, false)
        assertTrue(policy.observe(12_000, 4818, 4839, 0, true, false, false))
        // Corrupt timestamps can advertise a full buffer while playback remains BUFFERING.
        policy.clearObservation()
        policy.observe(20_000, 4818, 1_808_523, 0, true, false, false)
        assertTrue(policy.observe(32_000, 4818, 1_808_523, 0, true, false, false))
    }

    @Test fun targetsMovePastRepeatedOldGopAndStopAtBudget() {
        val policy = RecordedTsRecoveryPolicy()
        assertEquals(6818L, policy.nextTarget(4818, 1_808_523))
        assertEquals(11818L, policy.nextTarget(4818, 1_808_523))
        assertEquals(21818L, policy.nextTarget(4818, 1_808_523))
        assertEquals(41818L, policy.nextTarget(4818, 1_808_523))
        assertNull(policy.nextTarget(4818, 1_808_523))
        assertTrue(policy.exhausted)
    }

    @Test fun readyDoesNotResetBudgetButActualPlaybackDoes() {
        val policy = RecordedTsRecoveryPolicy()
        repeat(4) { policy.nextTarget(5000, 200_000) }
        policy.observe(0, 50_000, 60_000, 0, false, false, true)
        repeat(16) { i -> policy.observe((i + 1) * 1000L, 51_000L + i * 1000, 70_000, 0, false, false, true) }
        assertEquals(68_000L, policy.nextTarget(66_000, 200_000))
    }

    @Test fun endIsNotSkippedAndManualSeekCancelsOldRecovery() {
        val policy = RecordedTsRecoveryPolicy()
        assertNull(policy.nextTarget(9500, 10_000))
        policy.pendingError = true
        val serial = policy.seekSerial
        policy.userSeek()
        assertFalse(policy.exhausted)
        assertFalse(policy.pendingError)
        assertNotEquals(serial, policy.seekSerial)
        assertEquals(22_000L, policy.nextTarget(20_000, 100_000))
    }

    @Test fun readyWithoutPositionProgressCannotRefillBudget() {
        val policy = RecordedTsRecoveryPolicy()
        repeat(4) { policy.nextTarget(5000, 200_000) }
        repeat(20) { i -> policy.observe(i * 1000L, 50_000, 60_000, 0, false, false, false) }
        assertNull(policy.nextTarget(50_000, 200_000))
    }

    @Test fun pauseOrOfflineInvalidatesInFlightSeekWithoutRefillingBudget() {
        val policy = RecordedTsRecoveryPolicy()
        repeat(4) { policy.nextTarget(5000, 200_000) }
        val serial = policy.seekSerial
        policy.pendingError = true
        policy.cancelPending()
        assertNotEquals(serial, policy.seekSerial)
        assertFalse(policy.pendingError)
        assertNull(policy.nextTarget(50_000, 200_000))
    }
}
