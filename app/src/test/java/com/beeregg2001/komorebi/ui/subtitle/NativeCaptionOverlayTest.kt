package com.beeregg2001.komorebi.ui.subtitle

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NativeCaptionOverlayTest {
    @Test
    fun `subtitle in the movement path follows bottom avoidance`() {
        val regions = listOf(
            NativeCaptionRegion(x = 100, y = 100, width = 400, height = 120),
            NativeCaptionRegion(x = 500, y = 820, width = 600, height = 180),
            NativeCaptionRegion(x = 650, y = 750, width = 120, height = 50)
        )

        assertArrayEquals(
            booleanArrayOf(false, true, true),
            calculateBottomAvoidanceMask(
                regions,
                avoidanceStartY = 900f,
                avoidanceOffset = 200f
            )
        )
    }

    @Test
    fun `avoidance collision propagates through every blocking subtitle`() {
        val regions = listOf(
            NativeCaptionRegion(x = 400, y = 850, width = 500, height = 150),
            NativeCaptionRegion(x = 500, y = 700, width = 200, height = 100),
            NativeCaptionRegion(x = 550, y = 550, width = 100, height = 100),
            NativeCaptionRegion(x = 1_200, y = 550, width = 100, height = 100)
        )

        assertArrayEquals(
            booleanArrayOf(true, true, true, false),
            calculateBottomAvoidanceMask(
                regions,
                avoidanceStartY = 900f,
                avoidanceOffset = 200f
            )
        )
    }
}
