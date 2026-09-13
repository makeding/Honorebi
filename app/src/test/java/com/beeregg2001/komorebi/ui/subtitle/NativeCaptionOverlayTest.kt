package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativeCaptionOverlayTest {
    private fun region(x: Int, y: Int, w: Int = 100, h: Int = 50) = NativeCaptionRegion(x, y, w, h)
    private fun offsets(regions: List<NativeCaptionRegion>, obstacles: List<Rect>) =
        calculateCaptionObstacleOffsets(regions.map { listOf(it) }, obstacles).flatten()

    @Test fun `lower captions in gradient padding and control gaps remain stationary`() {
        val regions = listOf(region(10, 900), region(300, 900), region(800, 900), region(100, 700))
        assertEquals(List(4) { 0f }, offsets(regions,
            listOf(Rect(150f, 850f, 250f, 1000f), Rect(500f, 850f, 600f, 1000f))))
    }

    @Test fun `only intersecting captions shift by minimal distance and groups stay independent`() {
        assertEquals(listOf(30f, 80f, 0f), offsets(
            listOf(region(100, 850), region(500, 850), region(900, 850)),
            listOf(Rect(100f, 870f, 200f, 1000f), Rect(500f, 820f, 600f, 1000f))))
    }

    @Test fun `cross bitmap multilevel chain and touching ruby share full target`() {
        val bounds = listOf(region(100, 850, 800, 150), region(120, 700, 700, 100),
            region(120, 800, 40, 25), region(150, 550, 100, 100), region(1200, 100))
        val images = bounds.map { NativeCaptionImage(it.x, it.y, it.width, it.height,
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val regions = images.map(::captionAvoidanceRegions)
        assertEquals(bounds, regions.flatten())
        assertEquals(listOf(200f, 200f, 200f, 200f, 0f),
            calculateCaptionObstacleOffsets(regions, listOf(Rect(100f, 800f, 900f, 1000f))).flatten())
    }

    @Test fun `mixed B62 and B24 grouping is independent of input and obstacle order`() {
        val regions = listOf(region(100, 850, 300, 150), region(120, 700, 100, 100),
            region(130, 600, 40, 100), region(500, 900), region(1000, 700))
        val obstacles = listOf(Rect(100f, 800f, 400f, 1000f), Rect(500f, 930f, 600f, 1000f))
        val expected = listOf(200f, 200f, 200f, 20f, 0f)
        assertEquals(expected, calculateCaptionObstacleOffsets(listOf(regions.take(2), regions.drop(2)), obstacles).flatten())
        for (seed in 0..20) {
            val order = regions.indices.shuffled(kotlin.random.Random(seed))
            assertEquals(order.map { expected[it] }, offsets(order.map { regions[it] }, obstacles.reversed()))
        }
    }

    @Test fun `touching ruby above and below body moves without changing relative position`() {
        assertEquals(listOf(30f, 30f, 30f), offsets(
            listOf(region(100, 850), region(100, 830, 30, 20), region(100, 900, 30, 20)),
            listOf(Rect(100f, 890f, 200f, 1000f))))
    }

    @Test fun `top limit applies to entire group and keeps every region inside plane`() {
        assertEquals(listOf(10f, 10f), offsets(
            listOf(region(100, 100, 100, 100), region(100, 10, 100, 90)),
            listOf(Rect(100f, 50f, 200f, 300f))))
    }

    @Test fun `a merged group recalculates enough shift to clear controls for all members`() {
        assertEquals(listOf(120f, 120f), offsets(
            listOf(region(100, 850), region(100, 900, 100, 70)),
            listOf(Rect(100f, 850f, 200f, 1000f))))
    }

    @Test fun `minimal legal target can stop between separate controls`() {
        assertEquals(listOf(30f), offsets(listOf(region(100, 850)),
            listOf(Rect(100f, 870f, 200f, 950f), Rect(100f, 700f, 200f, 750f))))
    }

    @Test fun `target overlapping another control moves above it too`() {
        assertEquals(listOf(110f), offsets(listOf(region(100, 850)),
            listOf(Rect(100f, 870f, 200f, 950f), Rect(100f, 790f, 200f, 850f))))
    }

    @Test fun `empty or edge only obstacle contact does not move captions`() {
        val regions = listOf(region(100, 850))
        assertEquals(listOf(0f), offsets(regions, emptyList()))
        assertEquals(listOf(0f), offsets(regions, listOf(Rect(200f, 850f, 300f, 950f))))
    }
}
