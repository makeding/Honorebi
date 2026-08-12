package com.beeregg2001.komorebi.ui.video.components

import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesEpisodeMatrixContentTest {
    @Test
    fun matrix_alignsEpisodesAcrossChannelsAndLeavesMissingSlotsEmpty() {
        val programs = listOf(
            program(1, "1・2", "channel-a", "放送局 A"),
            program(2, "3", "channel-a", "放送局 A"),
            program(3, "2", "channel-b", "放送局 B"),
        )

        val matrix = buildSeriesEpisodeMatrix(programs)

        assertEquals(listOf("第1話", "第2話", "第3話"), matrix.slots.map { it.label })
        assertEquals(listOf(1, 1, 2), matrix.rows[0].programs.map { it?.id })
        assertEquals(listOf(null, 3, null), matrix.rows[1].programs.map { it?.id })
        assertNull(matrix.rows[1].programs.first())
    }

    @Test
    fun matrix_expandsEpisodeRange() {
        val matrix = buildSeriesEpisodeMatrix(listOf(program(4, "4-6", "channel-a", "放送局 A")))

        assertEquals(listOf("第4話", "第5話", "第6話"), matrix.slots.map { it.label })
        assertEquals(listOf(4, 4, 4), matrix.rows.single().programs.map { it?.id })
    }

    @Test
    fun matrix_prefersCompleteRecordingThenLongerPartialRecording() {
        val matrix = buildSeriesEpisodeMatrix(
            listOf(
                program(1, "3", "channel-a", "放送局 A", isPartiallyRecorded = true, recordedDuration = 1_000.0),
                program(2, "3", "channel-a", "放送局 A", recordedDuration = 600.0),
                program(3, "4", "channel-a", "放送局 A", isPartiallyRecorded = true, recordedDuration = 300.0),
                program(4, "4", "channel-a", "放送局 A", isPartiallyRecorded = true, recordedDuration = 500.0),
            ),
        )

        assertEquals(listOf(2, 4), matrix.rows.single().programs.map { it?.id })
    }

    private fun program(
        id: Int,
        episodeNumber: String,
        channelId: String,
        channelName: String,
        isPartiallyRecorded: Boolean = false,
        recordedDuration: Double = 1800.0,
    ) = RecordedProgram(
        id = id,
        title = "作品 第${episodeNumber}話",
        episodeNumber = episodeNumber,
        description = "",
        startTime = "2026-08-12T22:00:00+09:00",
        endTime = "2026-08-12T22:30:00+09:00",
        duration = 1800.0,
        isPartiallyRecorded = isPartiallyRecorded,
        channel = RecordedChannel(channelId, null, null, channelId, "BS", channelName, "1"),
        recordedVideo = RecordedVideo(id, "Recorded", "sample.ts", null, null, recordedDuration, "MPEG-TS", "H.264", "AAC"),
    )
}
