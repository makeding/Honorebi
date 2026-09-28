package com.beeregg2001.komorebi.ui.video.player.policy

import com.beeregg2001.komorebi.data.model.StreamQuality
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedMpegTsPassthroughPolicyTest {
    @Test
    fun originalMpegTsQualityUsesCodecIndependentDisplayName() {
        val quality = StreamQuality.originalMpegTs()

        assertEquals("オリジナル (MPEG-TS)", quality.label)
        assertEquals(StreamQuality.ORIGINAL_MPEG_TS_VALUE, quality.value)
        assertTrue(quality.isRawTs)
    }

    @Test
    fun originalMpegTsUsesTsReadExRegardlessOfVideoCodec() {
        assertTrue(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MPEG-TS",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MMT/TLV",
                StreamQuality.ORIGINAL_MPEG_TS_VALUE,
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "MPEG-TS",
                "1080p",
            )
        )
        assertFalse(
            RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
                "HLS",
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
                "-d", "9",
            ),
            RecordedMpegTsPassthroughPolicy.tsReadExArguments(101),
        )
    }
}
