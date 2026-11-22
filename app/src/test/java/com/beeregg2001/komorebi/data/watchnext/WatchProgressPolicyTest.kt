package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPolicyTest {

    @Test
    fun isInProgress_respectsMinThresholdOfFiveSeconds() {
        assertFalse(WatchProgressPolicy.isInProgress(0.0, 1800.0))
        assertFalse(WatchProgressPolicy.isInProgress(-1.0, 1800.0))
        assertFalse(WatchProgressPolicy.isInProgress(5.0, 1800.0))
        assertTrue(WatchProgressPolicy.isInProgress(5.01, 1800.0))
    }

    @Test
    fun isInProgress_respectsNinetyFivePercentCompletionThreshold() {
        val duration = 1000.0
        assertTrue(WatchProgressPolicy.isInProgress(949.99, duration))
        assertFalse(WatchProgressPolicy.isInProgress(950.0, duration))
        assertFalse(WatchProgressPolicy.isInProgress(1000.0, duration))
        assertFalse(WatchProgressPolicy.isInProgress(1200.0, duration))
    }

    @Test
    fun isInProgress_unknownOrNonPositiveDuration_checksOnlyLowerBound() {
        assertFalse(WatchProgressPolicy.isInProgress(5.0, null))
        assertTrue(WatchProgressPolicy.isInProgress(5.1, null))
        assertTrue(WatchProgressPolicy.isInProgress(3600.0, null))
        assertTrue(WatchProgressPolicy.isInProgress(10.0, 0.0))
        assertTrue(WatchProgressPolicy.isInProgress(10.0, -100.0))
        assertTrue(WatchProgressPolicy.isInProgress(10.0, Double.NaN))
    }

    @Test
    fun isInProgress_nonFinitePosition_returnsFalse() {
        assertFalse(WatchProgressPolicy.isInProgress(Double.NaN, 1800.0))
        assertFalse(WatchProgressPolicy.isInProgress(Double.POSITIVE_INFINITY, null))
        assertFalse(WatchProgressPolicy.isInProgress(Double.NEGATIVE_INFINITY, 1800.0))
    }

    @Test
    fun isCompleted_checksNinetyFivePercentThresholdAndValidInputs() {
        assertFalse(WatchProgressPolicy.isCompleted(949.99, 1000.0))
        assertTrue(WatchProgressPolicy.isCompleted(950.0, 1000.0))
        assertTrue(WatchProgressPolicy.isCompleted(1000.0, 1000.0))
        assertFalse(WatchProgressPolicy.isCompleted(950.0, null))
        assertFalse(WatchProgressPolicy.isCompleted(950.0, 0.0))
        assertFalse(WatchProgressPolicy.isCompleted(950.0, -10.0))
        assertFalse(WatchProgressPolicy.isCompleted(950.0, Double.NaN))
        assertFalse(WatchProgressPolicy.isCompleted(Double.NaN, 1000.0))
        assertFalse(WatchProgressPolicy.isCompleted(Double.POSITIVE_INFINITY, 1000.0))
    }

    @Test
    fun resumePositionSeconds_returnsPositionWhenInProgressElseZero() {
        assertEquals(0.0, WatchProgressPolicy.resumePositionSeconds(null, 1800.0), 0.0)
        assertEquals(0.0, WatchProgressPolicy.resumePositionSeconds(5.0, 1800.0), 0.0)
        assertEquals(120.5, WatchProgressPolicy.resumePositionSeconds(120.5, 1800.0), 0.0)
        assertEquals(0.0, WatchProgressPolicy.resumePositionSeconds(1710.0, 1800.0), 0.0)
        assertEquals(42.0, WatchProgressPolicy.resumePositionSeconds(42.0, null), 0.0)
    }

    @Test
    fun effectiveDurationSeconds_prefersVideoDurationThenProgramDuration() {
        assertEquals(1500.0, WatchProgressPolicy.effectiveDurationSeconds(program(videoDuration = 1500.0, programDuration = 1800.0)))
        assertEquals(1800.0, WatchProgressPolicy.effectiveDurationSeconds(program(videoDuration = 0.0, programDuration = 1800.0)))
        assertNull(WatchProgressPolicy.effectiveDurationSeconds(program(videoDuration = 0.0, programDuration = 0.0)))
        assertNull(WatchProgressPolicy.effectiveDurationSeconds(program(videoDuration = -5.0, programDuration = Double.NaN)))
    }

    private fun program(videoDuration: Double, programDuration: Double): RecordedProgram =
        RecordedProgram(
            id = 1,
            title = "Test",
            description = "",
            startTime = "2026-01-01T00:00:00+09:00",
            endTime = "2026-01-01T00:30:00+09:00",
            duration = programDuration,
            isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(
                id = 1,
                status = "Recorded",
                filePath = "/tmp/test.ts",
                duration = videoDuration,
                containerFormat = "MPEG-TS",
                videoCodec = "MPEG-2",
                audioCodec = "AAC",
            ),
        )
}
