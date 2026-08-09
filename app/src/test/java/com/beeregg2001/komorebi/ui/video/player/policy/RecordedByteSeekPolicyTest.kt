package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedByteSeekPolicyTest {
    @Test
    fun edcbDirect_onlyBecomesSeekableAfterHttpLengthIsKnown() {
        assertTrue(
            RecordedByteSeekPolicy.shouldWrapTsSeekMap(
                isEdcbDirect = true,
                isOriginalMpegTsPlayback = false,
                durationUs = 3_600_000_000L,
            )
        )
        assertFalse(RecordedByteSeekPolicy.isEstimatedByteSeekable(0L, 3_600_000_000L))
    }

    @Test
    fun originalHttpTs_canUseMapOnlyWithDurationAndLength() {
        assertTrue(
            RecordedByteSeekPolicy.shouldWrapTsSeekMap(
                isEdcbDirect = false,
                isOriginalMpegTsPlayback = true,
                durationUs = 3_600_000_000L,
            )
        )
        assertFalse(RecordedByteSeekPolicy.isEstimatedByteSeekable(0L, 3_600_000_000L))
        assertFalse(RecordedByteSeekPolicy.isEstimatedByteSeekable(1_000_000L, 0L))
        assertTrue(RecordedByteSeekPolicy.isEstimatedByteSeekable(1_000_000L, 3_600_000_000L))
    }

}
