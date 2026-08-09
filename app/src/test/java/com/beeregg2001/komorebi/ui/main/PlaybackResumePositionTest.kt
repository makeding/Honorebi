package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumePositionTest {

    @Test
    fun historyMatchedByProgramId_takesPriorityOverProgramPosition() {
        val program = recording(id = 42, videoId = 100, playbackPosition = 120.0)

        assertEquals(30_000L, playbackResumePositionMs(program, listOf(history("42", 30.0))))
    }

    @Test
    fun historyMatchedByVideoId_takesPriorityWhenProgramIdsDiffer() {
        val program = recording(id = 42, videoId = 100, playbackPosition = 120.0)

        assertEquals(30_000L, playbackResumePositionMs(program, listOf(history("other", 30.0, videoId = 100))))
    }

    @Test
    fun fallsBackToProgramPosition_whenNoUsableHistoryExists() {
        val program = recording(playbackPosition = 30.0)

        assertEquals(30_000L, playbackResumePositionMs(program, listOf(history("42", 5.0))))
    }

    @Test
    fun returnsStart_forPositionsAtOrBeforeFiveSeconds_orWithinFinalTenSeconds() {
        assertEquals(0L, playbackResumePositionMs(recording(playbackPosition = 5.0), emptyList()))
        assertEquals(0L, playbackResumePositionMs(recording(playbackPosition = 590.0), emptyList()))
    }

    @Test
    fun acceptsPosition_whenDurationIsUnknown() {
        assertEquals(
            30_000L,
            playbackResumePositionMs(recording(duration = 0.0, playbackPosition = 30.0), emptyList()),
        )
    }

    @Test
    fun negativePositionsNeverProduceANegativeInitialPosition() {
        assertEquals(0L, playbackResumePositionMs(recording(playbackPosition = -30.0), emptyList()))
        assertEquals(
            0L,
            playbackResumePositionMs(recording(), emptyList(), forcedPositionSeconds = -30.0),
        )
    }

    private fun recording(
        id: Int = 42,
        videoId: Int = 100,
        duration: Double = 600.0,
        playbackPosition: Double = 0.0,
    ) = RecordedProgram(
        id = id,
        title = "Test Recording",
        description = "",
        startTime = "2026-08-09T00:00:00+09:00",
        endTime = "2026-08-09T00:10:00+09:00",
        duration = duration,
        isPartiallyRecorded = false,
        playbackPosition = playbackPosition,
        recordedVideo = RecordedVideo(
            id = videoId,
            status = "Recorded",
            filePath = "/recordings/test.ts",
            duration = duration,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC-LC",
        ),
    )

    private fun history(programId: String, position: Double, videoId: Int? = null) = KonomiHistoryProgram(
        program = KonomiProgram(
            id = programId,
            title = "Test Recording",
            description = "",
            detail = null,
            start_time = "2026-08-09T00:00:00+09:00",
            end_time = "2026-08-09T00:10:00+09:00",
            channel_id = "gr011",
        ),
        playback_position = position,
        last_watched_at = "2026-08-09T00:00:00+09:00",
        videoId = videoId,
    )
}
