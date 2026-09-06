package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativeCaptionDrawCacheTest {
    private fun cue() = NativeCaptionCue(0, 5000, false, 1920, 1080, listOf(
        image(100, 850, 800, 150), image(120, 700, 700, 100),
        image(120, 800, 40, 25), image(1200, 100, 100, 100)
    ))
    private fun image(x: Int, y: Int, w: Int, h: Int) =
        NativeCaptionImage(x, y, w, h, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    @Test fun `repeated draws reuse geometry and preserve cross bitmap body and ruby offsets`() {
        val cache = NativeCaptionDrawCache(cue())
        val first = cache.get(Size(1920f, 1080f), 200, 900f / 1080)
        repeat(100) { assertSame(first, cache.get(Size(1920f, 1080f), 200, 900f / 1080)) }
        assertEquals(listOf(650, 500, 600, 100), first.map { it.offset.y })
        assertEquals(4, first.size)
        assertTrue(first.all { it.clip != null })
    }

    @Test fun `viewport boundary and offset invalidate cached geometry and hidden controls restore originals`() {
        val cue = cue()
        val cache = NativeCaptionDrawCache(cue)
        val moved = cache.get(Size(1920f, 1080f), 200, 900f / 1080)
        val original = cache.get(Size(1920f, 1080f), 0, 900f / 1080)
        assertNotSame(moved, original)
        assertEquals(cue.images.map { it.y }, original.map { it.offset.y })
        assertTrue(original.all { it.clip == null })
        val smaller = cache.get(Size(960f, 540f), 0, 1f)
        assertEquals(cue.images.map { it.y / 2 }, smaller.map { it.offset.y })
        val below = cache.get(Size(1920f, 1080f), 200, 1f)
        assertEquals(cue.images.map { it.y }, below.map { it.offset.y })
        assertEquals(listOf(650, 500, 600, 100), cache.get(Size(1920f, 1080f), 200, 900f / 1080).map { it.offset.y })
        assertTrue(NativeCaptionDrawCache(null).get(Size(1920f, 1080f), 200, 0.8f).isEmpty())
        assertTrue(NativeCaptionDrawCache(cue.copy(images = emptyList())).get(Size(1920f, 1080f), 200, 0.8f).isEmpty())
    }

    @Test fun `mixed B62 image retains stationary and shifted clips using the same bitmap`() {
        val image = image(0, 0, 1920, 1080).copy(regions = listOf(
            NativeCaptionRegion(100, 850, 800, 150), NativeCaptionRegion(1200, 100, 100, 100)
        ))
        val draws = NativeCaptionDrawCache(cue().copy(images = listOf(image)))
            .get(Size(1920f, 1080f), 200, 900f / 1080)
        assertEquals(listOf(0, -200), draws.map { it.offset.y })
        assertSame(draws[0].bitmap, draws[1].bitmap)
        assertNotSame(draws[0].clip, draws[1].clip)
        assertEquals(listOf(1920, 1920), draws.map { it.size.width })
    }
}
