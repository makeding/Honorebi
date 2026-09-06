package com.beeregg2001.komorebi.ui.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedControlProgressTest {

    @Test
    fun hiddenControls_doNotReadOrUpdateDisplayProgress() {
        val previous = RecordedControlsDisplayProgress(positionMs = 12_000L, bufferedPositionMs = 18_000L)
        var positionReads = 0
        var bufferedReads = 0

        val result = readRecordedControlsDisplayProgress(
            isVisible = false,
            previous = previous,
            positionMs = { positionReads++; 13_000L },
            bufferedPositionMs = { bufferedReads++; 19_000L },
        )

        assertFalse(shouldPollRecordedControlsDisplay(false))
        assertEquals(previous, result)
        assertEquals(0, positionReads)
        assertEquals(0, bufferedReads)
    }

    @Test
    fun visibleControls_readOnlyTheLocalDisplaySnapshot() {
        val result = readRecordedControlsDisplayProgress(
            isVisible = true,
            previous = RecordedControlsDisplayProgress(positionMs = 12_000L, bufferedPositionMs = 18_000L),
            positionMs = { 13_000L },
            bufferedPositionMs = { 19_000L },
        )

        assertTrue(shouldPollRecordedControlsDisplay(true))
        assertEquals(RecordedControlsDisplayProgress(13_000L, 19_000L), result)
    }

    @Test
    fun repeatedDirectionalSeek_usesTheLatestPendingTargetInsteadOfTheDisplayTick() {
        var latestPendingTargetMs = 120_000L

        repeat(2) {
            latestPendingTargetMs = resolveRecordedControlsSeekTarget(
                latestPositionMs = latestPendingTargetMs,
                stepMs = 10_000L,
                totalDurationMs = 1_800_000L,
                forward = true,
            )
        }

        assertEquals(140_000L, latestPendingTargetMs)
    }
}
