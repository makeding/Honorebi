package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgramInfoFormatterTest {

    @Test
    fun format_usesRecordedVideoTimesAndKeepsCrossDayDates_in24HourFormat() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(
                startTime = "2026-08-10T23:55:00+09:00",
                endTime = "2026-08-11T00:25:00+09:00",
                recordingStartTime = "2026-08-10T23:53:30+09:00",
                recordingEndTime = "2026-08-11T00:27:00+09:00"
            ),
            "24H"
        )

        assertEquals(
            listOf(
                PlaybackProgramInfoRow("チャンネル", "NHK総合 ・ GR011"),
                PlaybackProgramInfoRow("放送日時", "2026/08/10(月) 23:55 - 2026/08/11(火) 00:25"),
                PlaybackProgramInfoRow("実際の録画日時", "2026/08/10(月) 23:53 - 2026/08/11(火) 00:27"),
                PlaybackProgramInfoRow("長さ", "30:00"),
                PlaybackProgramInfoRow("状態", "録画中 ・ 部分録画")
            ),
            rows
        )
    }

    @Test
    fun format_includesCrossDayDates_in12HourFormat() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(
                startTime = "2026-08-10T23:55:00+09:00",
                endTime = "2026-08-11T00:25:00+09:00",
                recordingStartTime = "2026-08-10T23:53:30+09:00",
                recordingEndTime = "2026-08-11T00:27:00+09:00"
            ),
            "12H"
        )

        assertEquals("2026/08/10(月) 午後 11:55 - 2026/08/11(火) 午前 12:25", rows.valueFor("放送日時"))
        assertEquals("2026/08/10(月) 午後 11:53 - 2026/08/11(火) 午前 12:27", rows.valueFor("実際の録画日時"))
    }

    @Test
    fun format_neverUsesProgramScheduleWhenActualRecordingTimesAreMissing() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(recordingStartTime = null, recordingEndTime = null),
            "24H"
        )

        assertEquals("2026/08/10(月) 13:05 - 14:35", rows.valueFor("放送日時"))
        assertEquals("記録されていません", rows.valueFor("実際の録画日時"))
    }

    @Test
    fun format_keepsActualRecordingSlotWhenTimestampsAreMalformed() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(recordingStartTime = "not-an-iso-time", recordingEndTime = "also-invalid"),
            "24H"
        )

        assertEquals("記録されていません", rows.valueFor("実際の録画日時"))
    }

    @Test
    fun format_marksTheMissingActualStartWithoutBorrowingTheProgramStart() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(recordingStartTime = null, recordingEndTime = "2026-08-10T14:36:00+09:00"),
            "24H"
        )

        assertEquals(
            "開始時刻未記録 - 2026/08/10(月) 14:36",
            rows.valueFor("実際の録画日時")
        )
    }

    @Test
    fun format_marksTheMissingActualEndWithoutBorrowingTheProgramEnd() {
        val rows = PlaybackProgramInfoFormatter.format(
            program(recordingStartTime = "2026-08-10T13:04:00+09:00", recordingEndTime = null),
            "24H"
        )

        assertEquals(
            "2026/08/10(月) 13:04 - 終了時刻未記録",
            rows.valueFor("実際の録画日時")
        )
    }

    private fun List<PlaybackProgramInfoRow>.valueFor(label: String): String =
        single { it.label == label }.value

    private fun program(
        startTime: String = "2026-08-10T13:05:00+09:00",
        endTime: String = "2026-08-10T14:35:00+09:00",
        recordingStartTime: String? = "2026-08-10T13:04:00+09:00",
        recordingEndTime: String? = "2026-08-10T14:36:00+09:00"
    ) = RecordedProgram(
        id = 1,
        title = "番組",
        description = "説明",
        startTime = startTime,
        endTime = endTime,
        duration = 5_400.0,
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
            recordingStartTime = recordingStartTime,
            recordingEndTime = recordingEndTime,
            duration = 1_800.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC"
        )
    )
}
