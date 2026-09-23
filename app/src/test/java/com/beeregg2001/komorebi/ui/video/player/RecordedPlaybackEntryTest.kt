package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.StreamQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedPlaybackEntryTest {
    private val hls = StreamQuality.DEFAULT_QUALITIES.first()
    private val raw = StreamQuality.recordedRawMmts()
    private val copyHls = StreamQuality.recordedCopyHls()

    @Test
    fun cachedRecordingWaitsForDetailAndMatchingQuality() {
        val cached = program(42, "")
        assertFalse(canResolveRecordedPlaybackUrl(cached, "KONOMITV", 42, true, listOf(hls), hls))
        // A failed detail request leaves the cached container empty, including after retry.
        assertFalse(canResolveRecordedPlaybackUrl(cached, "KONOMITV", 42, true, listOf(raw), raw))

        val detail = program(42, "MMT/TLV")
        assertFalse(canResolveRecordedPlaybackUrl(detail, "KONOMITV", 41, true, listOf(raw), raw))
        assertFalse(canResolveRecordedPlaybackUrl(detail, "KONOMITV", 42, false, listOf(raw), raw))
        assertFalse(canResolveRecordedPlaybackUrl(detail, "KONOMITV", 42, true, listOf(hls), raw))
        assertTrue(canResolveRecordedPlaybackUrl(detail, "KONOMITV", 42, true, listOf(raw), raw))
    }

    @Test
    fun detailForAnotherProgramCannotStartOldUrl() {
        assertFalse(canResolveRecordedPlaybackUrl(program(43, "MPEG-TS"), "KONOMITV", 42,
            true, listOf(hls), hls))
        assertTrue(canResolveRecordedPlaybackUrl(program(43, "MPEG-TS"), "KONOMITV", 43,
            true, listOf(hls), hls))
    }

    @Test
    fun selectedSourcesHaveExpectedRecordedEndpoints() {
        assertTrue(UrlBuilder.getVideoRawMmtsUrl("tv.example", "443", 42)
            .endsWith("/api/streams/video/42/raw-mmts/mpegts"))
        assertTrue(UrlBuilder.getVideoPlaylistUrl("tv.example", "443", 42, "session", hls.value)
            .contains("/api/streams/video/42/${hls.value}/playlist?session_id=session"))
        assertTrue(UrlBuilder.getVideoPlaylistUrl("tv.example", "443", 42, "session", copyHls.value)
            .contains("/api/streams/video/42/copy/playlist?session_id=session"))
        assertTrue(canResolveRecordedPlaybackUrl(program(42, "MMT/TLV"), "KONOMITV", 42,
            true, listOf(raw, copyHls), copyHls))
    }

    @Test
    fun completedMmtStartsRawButKeepsManualHlsSelection() {
        val qualities = listOf(raw, copyHls)
        assertEquals(raw, chooseRecordedQuality(qualities, hls, "copy", false, true, false))
        assertEquals(copyHls, chooseRecordedQuality(qualities, copyHls, "1080p", false, true, true))
        assertEquals(raw, chooseRecordedQuality(listOf(raw), copyHls, "copy", false, true, true))
    }

    private fun program(id: Int, container: String) = RecordedProgram(
        id = id,
        title = "録画",
        description = "",
        startTime = "2026-09-23T00:00:00+09:00",
        endTime = "2026-09-23T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(id, "Recorded", "/recording", duration = 3600.0,
            containerFormat = container, videoCodec = "HEVC", audioCodec = "AAC"),
    )
}
