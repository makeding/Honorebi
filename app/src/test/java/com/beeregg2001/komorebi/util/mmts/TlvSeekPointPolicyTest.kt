package com.beeregg2001.komorebi.util.mmts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlvSeekPointPolicyTest {
    @Test
    fun observed4kPoint_26SecondsBehindIsRejected() {
        assertFalse(
            shouldUseIndexedTlvSeekPoint(
                distanceFromIndexedPointUs = 361_470_000L - 334_636_733L,
                recordingDurationUs = 1_815_065_677L,
                inputLengthBytes = 6_605_983_763L,
                hasFollowingIndexedPoint = false,
            )
        )
    }

    @Test
    fun nearbyIndexedPointWithinScanBudgetIsUsed() {
        assertTrue(
            shouldUseIndexedTlvSeekPoint(
                distanceFromIndexedPointUs = 4_000_000L,
                recordingDurationUs = 1_815_065_677L,
                inputLengthBytes = 6_605_983_763L,
                hasFollowingIndexedPoint = false,
            )
        )
    }

    @Test
    fun bracketingIndexIsAlwaysPreferred() {
        assertTrue(
            shouldUseIndexedTlvSeekPoint(
                distanceFromIndexedPointUs = 60_000_000L,
                recordingDurationUs = 1_815_065_677L,
                inputLengthBytes = 6_605_983_763L,
                hasFollowingIndexedPoint = true,
            )
        )
    }
}
