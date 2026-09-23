package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedHlsQualityTest {
    @Test
    fun copyHlsIsOfferedOnlyAfterMmtRecordingCompletes() {
        assertEquals(
            listOf(StreamQuality.RAW_MMTS_PRIMARY_VALUE, StreamQuality.RECORDED_COPY_HLS_VALUE),
            buildMmtRecordedQualities(program("Recorded", false)).map { it.value },
        )
        assertEquals(
            listOf(StreamQuality.RAW_MMTS_PRIMARY_VALUE),
            buildMmtRecordedQualities(program("Recording", true)).map { it.value },
        )
        assertEquals(
            listOf(StreamQuality.RAW_MMTS_PRIMARY_VALUE),
            buildMmtRecordedQualities(program("Recording", false)).map { it.value },
        )
    }

    private fun program(status: String, isRecording: Boolean) = RecordedProgram(
        id = 42,
        title = "録画",
        description = "",
        startTime = "2026-09-23T00:00:00+09:00",
        endTime = "2026-09-23T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        isRecording = isRecording,
        recordedVideo = RecordedVideo(42, status, "/recording", duration = 3600.0,
            containerFormat = "MMT/TLV", videoCodec = "HEVC", audioCodec = "AAC"),
    )
}
