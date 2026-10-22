package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.CmSkipMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCmSkipPolicyTest {
    private val cm = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 25_000L, isCm = true)

    @Test
    fun pollingOnlyRunsForPlayingAutoModeWithChapters() {
        val main = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 25_000L, isCm = false)
        val tooShortCm = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 11_500L, isCm = true)

        assertTrue(shouldRunAutoCmSkipPolling(CmSkipMode.AUTO, listOf(cm), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.OFF, listOf(cm), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.MANUAL, listOf(cm), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.AUTO, emptyList(), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.AUTO, listOf(main), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.AUTO, listOf(tooShortCm), isPlaying = true))
        assertFalse(shouldRunAutoCmSkipPolling(CmSkipMode.AUTO, listOf(cm), isPlaying = false))
    }

    @Test
    fun targetPreservesCmEndGuard() {
        assertEquals(25_000L, autoCmSkipTargetMs(10_000L, listOf(cm)))
        assertEquals(25_000L, autoCmSkipTargetMs(23_499L, listOf(cm)))
        assertNull(autoCmSkipTargetMs(23_500L, listOf(cm)))
    }

    @Test
    fun targetIgnoresPositionsOutsideCmAndNonCmChapters() {
        val main = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 25_000L, isCm = false)

        assertNull(autoCmSkipTargetMs(9_999L, listOf(cm)))
        assertNull(autoCmSkipTargetMs(10_000L, listOf(main)))
        assertNull(autoCmSkipTargetMs(25_000L, listOf(cm)))
    }
}
