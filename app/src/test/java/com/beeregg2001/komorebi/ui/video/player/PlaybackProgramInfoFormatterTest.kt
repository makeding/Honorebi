package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgramInfoFormatterTest {

    @Test
    fun format_includesChannelScheduleDurationAndRecordingStates_in24HourFormat() {
        val meta = formatPlaybackProgramMeta(program(), "24H")

        assertEquals(
            "NHK総合 ・ GR011 · 2026/08/10(月) 13:05 - 14:35 · 録画中 ・ 部分録画",
            meta
        )
    }

    @Test
    fun format_uses12HourFormatAndFallsBackToProgramDuration() {
        val meta = formatPlaybackProgramMeta(
            program(recordedDuration = 0.0, duration = 65.0),
            "12H"
        )

        assertEquals(
            "NHK総合 ・ GR011 · 2026/08/10(月) 午後 1:05 - 午後 2:35 · 録画中 ・ 部分録画",
            meta
        )
    }

    @Test
    fun format_omitsMalformedScheduleInsteadOfThrowing() {
        val meta = formatPlaybackProgramMeta(program(startTime = "not-an-iso-time", endTime = "also-invalid"), "24H")

        assertEquals(
            "NHK総合 ・ GR011 · 録画中 ・ 部分録画",
            meta
        )
    }

    @Test
    fun format_keepsValidStartWhenOnlyEndTimeIsMalformed() {
        val meta = formatPlaybackProgramMeta(program(endTime = "invalid"), "24H")

        assertEquals(
            "NHK総合 ・ GR011 · 2026/08/10(月) 13:05 · 録画中 ・ 部分録画",
            meta
        )
    }

    private fun program(
        startTime: String = "2026-08-10T13:05:00+09:00",
        endTime: String = "2026-08-10T14:35:00+09:00",
        duration: Double = 5_400.0,
        recordedDuration: Double = 5_400.0
    ) = RecordedProgram(
        id = 1,
        title = "番組",
        description = "説明",
        startTime = startTime,
        endTime = endTime,
        duration = duration,
        isPartiallyRecorded = true,
        isRecording = true,
        channel = RecordedChannel(
            id = "1",
            displayChannelId = "GR011",
            type = "GR",
            name = "NHK総合",
            channelNumber = "011"
        ),
        recordedVideo = RecordedVideo(
            id = 1,
            status = "Recording",
            filePath = "/recorded.ts",
            duration = recordedDuration,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC"
        )
    )
}
