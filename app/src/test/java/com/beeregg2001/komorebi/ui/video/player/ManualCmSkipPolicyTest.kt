package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.CmSkipMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualCmSkipPolicyTest {
    private val cm = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 25_000L, isCm = true)

    @Test
    fun manualWindowIncludesStartAndLastMillisecond() {
        assertEquals(25_000L, manualCmSkipTargetMs(CmSkipMode.MANUAL, 10_000L, listOf(cm)))
        assertEquals(25_000L, manualCmSkipTargetMs(CmSkipMode.MANUAL, 14_999L, listOf(cm)))
    }

    @Test
    fun manualWindowEndsAtFiveSeconds() {
        assertNull(manualCmSkipTargetMs(CmSkipMode.MANUAL, 15_000L, listOf(cm)))
    }

    @Test
    fun shortCmWindowNeverExtendsIntoMainProgram() {
        val shortCm = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 12_000L, isCm = true)

        assertEquals(12_000L, manualCmSkipTargetMs(CmSkipMode.MANUAL, 11_999L, listOf(shortCm)))
        assertNull(manualCmSkipTargetMs(CmSkipMode.MANUAL, 12_000L, listOf(shortCm)))
    }

    @Test
    fun otherModesNonCmAndBlockedInteractionsHaveNoTarget() {
        val mainProgram = ChapterInfo(startTimeMs = 10_000L, endTimeMs = 25_000L, isCm = false)

        assertNull(manualCmSkipTargetMs(CmSkipMode.OFF, 10_000L, listOf(cm)))
        assertNull(manualCmSkipTargetMs(CmSkipMode.AUTO, 10_000L, listOf(cm)))
        assertNull(manualCmSkipTargetMs(CmSkipMode.MANUAL, 10_000L, listOf(mainProgram)))
        assertNull(
            manualCmSkipTargetMs(
                CmSkipMode.MANUAL,
                10_000L,
                listOf(cm),
                interactionBlocked = true
            )
        )
    }
}
