package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveHistoryTrackerTest {
    @Test
    fun mapsFirstObservationBehindTheLiveEdgeThenFollowsMediaTimeline() {
        val tracker = LiveHistoryTracker()
        tracker.observe("gr27", mediaPositionMs = 30_000, bufferedPositionMs = 35_000, nowEpochMs = 1_000_000)
        tracker.observe("gr27", mediaPositionMs = 38_000, bufferedPositionMs = 43_000, nowEpochMs = 1_100_000)

        val progress = requireNotNull(tracker.latest())
        assertEquals("gr27", progress.channel_id)
        assertEquals(1_003.0, progress.playback_time, 0.0)
    }

    @Test
    fun pausedOrStalledObservationCannotInventWallClockProgress() {
        val tracker = LiveHistoryTracker()
        assertEquals(false, tracker.observe("gr27", mediaPositionMs = 30_000, bufferedPositionMs = 35_000, nowEpochMs = 1_000_000))
        assertEquals(true, tracker.observe("gr27", mediaPositionMs = 31_000, bufferedPositionMs = 36_000, nowEpochMs = 1_001_000))
        // The caller only invokes observe while Media3 reports isPlaying; this unchanged media
        // sample represents a later paused/stalled checkpoint and remains at the old wall-clock.
        assertEquals(false, tracker.observe("gr27", mediaPositionMs = 31_000, bufferedPositionMs = 36_000, nowEpochMs = 1_100_000))

        assertEquals(996.0, requireNotNull(tracker.latest()).playback_time, 0.0)
    }

    @Test
    fun clearReturnsLastMediaObservationAndDoesNotLeakItToNextChannel() {
        val tracker = LiveHistoryTracker()
        tracker.observe("gr27", 30_000, 30_000, 1_000_000)
        tracker.observe("gr27", 31_000, 31_000, 1_001_000)
        assertEquals("gr27", requireNotNull(tracker.clear()).channel_id)
        assertNull(tracker.latest())
    }

    @Test
    fun mediaTimelineResetReanchorsInsteadOfReportingAStaleProgrammeTime() {
        val tracker = LiveHistoryTracker()
        tracker.observe("gr27", 60_000, 60_000, 1_000_000)
        tracker.observe("gr27", 2_000, 2_000, 1_100_000)

        assertEquals(1_100.0, requireNotNull(tracker.latest()).playback_time, 0.0)
    }

    @Test
    fun advancingSecondSampleMakesTheFirstCheckpointEligible() {
        val tracker = LiveHistoryTracker()
        assertEquals(false, tracker.observe("gr27", 30_000, 35_000, 1_000_000))
        assertEquals(true, tracker.observe("gr27", 31_000, 36_000, 1_001_000))
    }

    @Test
    fun longPauseThenOneSecondOfMediaAdvancementAddsOnlyOneSecond() {
        val tracker = LiveHistoryTracker()
        tracker.observe("gr27", 30_000, 35_000, 1_000_000)
        tracker.observe("gr27", 31_000, 36_000, 1_001_000)
        // The VM does not re-anchor during this pause/buffering gap.
        tracker.observe("gr27", 31_000, 36_000, 1_601_000)
        tracker.observe("gr27", 32_000, 37_000, 1_602_000)

        assertEquals(997.0, requireNotNull(tracker.latest()).playback_time, 0.0)
    }
}
