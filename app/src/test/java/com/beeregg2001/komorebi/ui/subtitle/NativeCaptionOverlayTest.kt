package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativeCaptionOverlayTest {
    @Test
    fun `B24 separate images propagate movement through body and touching ruby`() {
        val bounds = listOf(
            NativeCaptionRegion(100, 850, 800, 150),
            NativeCaptionRegion(120, 700, 700, 100),
            NativeCaptionRegion(120, 800, 40, 25),
            NativeCaptionRegion(150, 550, 100, 100),
            NativeCaptionRegion(1200, 100, 100, 100)
        )
        val images = bounds.map {
            NativeCaptionImage(it.x, it.y, it.width, it.height,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        val regions = images.map(::captionAvoidanceRegions)
        assertEquals(bounds, regions.flatten())
        val masks = calculateCueBottomAvoidanceMasks(regions, 900f, 200f)
        assertArrayEquals(booleanArrayOf(true, true, true, true, false),
            masks.map { it.single() }.toBooleanArray())
        calculateCueBottomAvoidanceMasks(regions, 900f, 0f).forEach {
            assertArrayEquals(booleanArrayOf(false), it)
        }
    }

    @Test
    fun `mixed image regions share one order independent collision closure`() {
        val regions = listOf(
            listOf(NativeCaptionRegion(550, 550, 100, 100),
                NativeCaptionRegion(1200, 100, 100, 100)),
            listOf(NativeCaptionRegion(500, 700, 200, 100)),
            listOf(NativeCaptionRegion(400, 850, 500, 150))
        )
        val masks = calculateCueBottomAvoidanceMasks(regions, 900f, 200f)
        assertArrayEquals(booleanArrayOf(true, false), masks[0])
        assertArrayEquals(booleanArrayOf(true), masks[1])
        assertArrayEquals(booleanArrayOf(true), masks[2])
    }

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
