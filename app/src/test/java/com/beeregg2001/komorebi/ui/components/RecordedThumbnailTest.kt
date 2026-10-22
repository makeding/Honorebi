package com.beeregg2001.komorebi.ui.components

import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.*
import org.junit.Test

class RecordedThumbnailTest {
    private val program = RecordedProgram(
        id = 1, title = "番組", description = "", startTime = "", endTime = "",
        duration = 1800.0, isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(1, "Recording", "/1.ts", duration = 60.0,
            containerFormat = "MPEG-TS", videoCodec = "H.264", audioCodec = "AAC"),
    )

    @Test fun recordingRequestsRemoteImageAndRefreshesAfterGrowth() {
        val url = "https://example.test/thumbnail?token=abc#image"
        val initial = program.recordedThumbnailModel(url)
        assertEquals("https://example.test/thumbnail?token=abc&recording_revision=2#image", initial)
        assertEquals(initial, program.recordedThumbnailCacheKey(url))
        val growing = program.copy(recordedVideo = program.recordedVideo.copy(duration = 330.0))
        assertNotEquals(initial, growing.recordedThumbnailModel(url))
        assertEquals(growing.recordedThumbnailModel(url), growing.recordedThumbnailCacheKey(url))
    }

    @Test fun completionUsesFinalImageWithoutRecordingCache() {
        val url = "https://example.test/thumbnail"
        val completed = program.copy(recordedVideo = program.recordedVideo.copy(status = "Recorded"))
        assertEquals(url, completed.recordedThumbnailModel(url))
        assertEquals(url, completed.recordedThumbnailCacheKey(url))
        assertNotEquals(program.recordedThumbnailCacheKey(url), completed.recordedThumbnailCacheKey(url))
        assertNull(program.recordedThumbnailModel(null))
        assertTrue(completed.copy(isRecording = true).recordedThumbnailModel(url)!!.contains("recording_revision="))
    }
}
