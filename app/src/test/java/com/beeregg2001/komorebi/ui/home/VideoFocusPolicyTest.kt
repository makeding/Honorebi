package com.beeregg2001.komorebi.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFocusPolicyTest {
    @Test
    fun playerReturnPrefersTheRowWhereTheProgramWasOpened() {
        val result = VideoFocusPolicy.restoreTarget(
            requestedProgramId = "42",
            priorTarget = VideoCardFocus(VideoFocusRow.HISTORY, "42"),
            recentProgramIds = listOf("42", "9"),
            historyProgramIds = listOf("42"),
        )

        assertEquals(VideoCardFocus(VideoFocusRow.HISTORY, "42"), result)
        assertEquals(2, VideoFocusPolicy.columnItemIndex(result!!, hasRecent = true))
        assertEquals(0, VideoFocusPolicy.rowItemIndex(result, listOf("42", "9"), listOf("42")))
    }

    @Test
    fun playerReturnUsesTheActuallyExitedProgramAndPrefersRecentOverItsOldRow() {
        assertEquals(
            VideoCardFocus(VideoFocusRow.RECENT, "B"),
            VideoFocusPolicy.playerReturnTarget(
                playedProgramId = "B",
                recentProgramIds = listOf("A", "B"),
                historyProgramIds = listOf("B", "A"),
            ),
        )
    }

    @Test
    fun playerReturnFallsBackFromMissingRecentProgramToHistoryThenAvailableCard() {
        assertEquals(
            VideoCardFocus(VideoFocusRow.HISTORY, "B"),
            VideoFocusPolicy.playerReturnTarget("B", listOf("A"), listOf("B", "C")),
        )
        assertEquals(
            VideoCardFocus(VideoFocusRow.HISTORY, "C"),
            VideoFocusPolicy.playerReturnTarget("B", listOf("A"), listOf("C")),
        )
        assertEquals(
            VideoCardFocus(VideoFocusRow.RECENT, "A"),
            VideoFocusPolicy.playerReturnTarget("B", listOf("A"), emptyList()),
        )
        assertNull(VideoFocusPolicy.playerReturnTarget("B", emptyList(), emptyList()))
    }

    @Test
    fun playerReturnKeepsBPendingUntilBothSourcesHaveResolved() {
        assertEquals(
            true,
            VideoFocusPolicy.shouldWaitForPlayerReturn(
                playedProgramId = "B",
                recentProgramIds = listOf("A"),
                historyProgramIds = listOf("B"),
                recentLoading = true,
                historyReady = true,
            ),
        )
        assertEquals(
            true,
            VideoFocusPolicy.shouldWaitForPlayerReturn(
                playedProgramId = "B",
                recentProgramIds = listOf("A"),
                historyProgramIds = listOf("C"),
                recentLoading = true,
                historyReady = true,
            ),
        )
        assertEquals(
            true,
            VideoFocusPolicy.shouldWaitForPlayerReturn(
                playedProgramId = "B",
                recentProgramIds = listOf("A"),
                historyProgramIds = emptyList(),
                recentLoading = false,
                historyReady = false,
            ),
        )
        assertEquals(
            false,
            VideoFocusPolicy.shouldWaitForPlayerReturn(
                playedProgramId = "B",
                recentProgramIds = listOf("A", "B"),
                historyProgramIds = emptyList(),
                recentLoading = true,
                historyReady = false,
            ),
        )
    }

    @Test
    fun playerReturnFallsBackToTheRecordedRowWhenOriginIsUnavailable() {
        assertEquals(
            VideoCardFocus(VideoFocusRow.RECENT, "8"),
            VideoFocusPolicy.restoreTarget(
                requestedProgramId = "8",
                priorTarget = VideoCardFocus(VideoFocusRow.HISTORY, "8"),
                recentProgramIds = listOf("8"),
                historyProgramIds = emptyList(),
            ),
        )
    }

    @Test
    fun aiReturnUsesTheFocusedCardOnlyWhileItStillExists() {
        assertEquals(
            VideoCardFocus(VideoFocusRow.RECENT, "4"),
            VideoFocusPolicy.restoreTarget(null, VideoCardFocus(VideoFocusRow.RECENT, "4"), listOf("4"), emptyList()),
        )
        assertNull(
            VideoFocusPolicy.restoreTarget(null, VideoCardFocus(VideoFocusRow.RECENT, "4"), emptyList(), emptyList()),
        )
    }
}
