package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedTimelineDurationPolicyTest {
    @Test
    fun rawMmts_usesLibaribtlvDurationInsteadOfKonomiReportedDuration() {
        assertEquals(
            1_815_065L,
            resolveCompletedRecordingTimelineDurationMs(
                isRawMmtsPlayback = true,
                konomiReportedDurationMs = 3_600_000L,
                nativePlayerDurationMs = 1_815_065L,
                playbackPositionMs = 421_482L,
                bufferedPositionMs = 423_274L,
            )
        )
    }

    @Test
    fun ordinaryRecording_keepsKonomiDurationAsAValidInput() {
        assertEquals(
            3_600_000L,
            resolveCompletedRecordingTimelineDurationMs(
                isRawMmtsPlayback = false,
                konomiReportedDurationMs = 3_600_000L,
                nativePlayerDurationMs = 3_599_000L,
                playbackPositionMs = 1_000L,
                bufferedPositionMs = 2_000L,
            )
        )
    }

    @Test
    fun timeline_neverFallsBehindObservedPlayback() {
        assertEquals(
            1_900_000L,
            resolveCompletedRecordingTimelineDurationMs(
                isRawMmtsPlayback = true,
                konomiReportedDurationMs = 0L,
                nativePlayerDurationMs = 1_815_065L,
                playbackPositionMs = 1_900_000L,
                bufferedPositionMs = 1_850_000L,
            )
        )
    }
}
