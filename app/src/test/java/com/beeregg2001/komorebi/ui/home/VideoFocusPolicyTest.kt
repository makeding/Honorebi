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
