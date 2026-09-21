package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.*
import org.junit.Test

class LivePlaybackHealthTest {
    @Test fun requiresTenSecondsOfAdvancingPlayback() {
        val health = LivePlaybackHealth()
        for (i in 0L..9L) assertFalse(health.observe(i * 1000, true, i * 1000))
        assertTrue(health.observe(10_000, true, 10_000))
        health.reset()
        assertFalse(health.observe(11_000, true, 11_000))
    }

    @Test fun readyWithoutPlaybackAndStallsCannotResetBudget() {
        val health = LivePlaybackHealth()
        for (i in 0L..20L) assertFalse(health.observe(i * 1000, false, i * 1000))
        for (i in 21L..40L) assertFalse(health.observe(i * 1000, true, 1000))
    }

    @Test fun pausePositionDiscontinuityAndSamplingGapRestartTheWindow() {
        for (interruption in 0..2) {
            val health = LivePlaybackHealth()
            for (i in 0L..9L) health.observe(i * 1000, true, i * 1000)
            when (interruption) {
                0 -> assertFalse(health.observe(10_000, false, 10_000))
                1 -> assertFalse(health.observe(10_000, true, 0))
                2 -> assertFalse(health.observe(13_000, true, 13_000))
            }
            assertFalse(health.observe(14_000, true, 14_000))
        }
    }
}
