package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiProgressGateTest {
    @Test
    fun reportsEarlyPositionsAndContinuesAfterEachSuccessfulEpisode() {
        val gate = BangumiProgressGate()
        assertTrue(gate.begin(1, 1_000L, 10_800_000L, 0L))
        assertFalse(gate.begin(1, 1_200_000L, 10_800_000L, 30_000L))
        gate.finish(1)
        assertTrue(gate.begin(1, 1_200_000L, 10_800_000L, 30_000L))
        gate.finish(1)
        assertTrue(gate.begin(1, 3_000_000L, 10_800_000L, 60_000L))
    }

    @Test
    fun limitsRetriesAndAllowsANewProgramWithoutWaiting() {
        val gate = BangumiProgressGate()
        assertFalse(gate.begin(1, 0L, 0L, 0L))
        assertFalse(gate.begin(1, -1L, 1_800_000L, 0L))
        assertTrue(gate.begin(1, 0L, 1_800_000L, 0L))
        gate.finish(1)
        assertFalse(gate.begin(1, 1L, 1_800_000L, 29_999L))
        assertTrue(gate.begin(2, 1L, 1_800_000L, 29_999L))
        gate.finish(1)
        assertFalse(gate.begin(2, 2L, 1_800_000L, 60_000L))
        gate.finish(2)
        assertTrue(gate.begin(2, 2L, 1_800_000L, 60_000L))
    }
}
