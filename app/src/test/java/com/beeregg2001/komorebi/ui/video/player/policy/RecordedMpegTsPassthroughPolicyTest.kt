package com.beeregg2001.komorebi.ui.video.player.policy

import com.beeregg2001.komorebi.data.model.StreamQuality
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedMpegTsPassthroughPolicyTest {
    @Test
    fun originalMpeg2QualityUsesTheUpstreamDisplayName() {
        val quality = StreamQuality.originalMpegTsHardwareDi()

        assertEquals("オリジナル (MPEG-2)", quality.label)
        assertEquals(StreamQuality.ORIGINAL_MPEG_TS_VALUE, quality.value)
        assertTrue(quality.isRawTs)
    }

    @Test
    fun onlyOriginalMpeg2TransportStreamUsesTsReadEx() {
        assertTrue(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MPEG-TS",
                "MPEG-2",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )

        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MPEG-TS",
                "H.264",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MMT/TLV",
                "HEVC",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MPEG-TS",
                "MPEG-2",
                "1080p",
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "HLS",
                "MPEG-2",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )
    }

    @Test
    fun argumentsPreserveTheRecordedServiceAndInformationTracks() {
        assertArrayEquals(
            arrayOf(
                "tsreadex",
                "-x", "18/38/39",
                "-n", "101",
                "-a", "13",
                "-b", "5",
                "-c", "5",
                "-u", "1",
                "-d", "13",
            ),
            RecordedMpegTsPassthroughPolicy.tsReadExArguments(101),
        )
    }
}
