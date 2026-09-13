package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativeCaptionDrawCacheTest {
    private val obstacles = listOf(Rect(100f, 800f, 900f, 1000f))
    private fun cue() = NativeCaptionCue(0, 5000, false, 1920, 1080, listOf(
        image(100, 850, 800, 150), image(120, 700, 700, 100),
        image(120, 800, 40, 25), image(1200, 100, 100, 100)
    ))
    private fun image(x: Int, y: Int, w: Int, h: Int) =
        NativeCaptionImage(x, y, w, h, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    @Test fun `repeated draws reuse geometry and preserve cross bitmap body and ruby offsets`() {
        val cache = NativeCaptionDrawCache(cue())
        val first = cache.get(Size(1920f, 1080f), obstacles, 1f)
        repeat(100) { assertSame(first, cache.get(Size(1920f, 1080f), obstacles, 1f)) }
        assertEquals(listOf(650, 500, 600, 100), first.map { it.offset.y })
        assertEquals(4, first.size)
        assertTrue(first.take(3).all { it.clip != null })
        assertNull(first.last().clip)
    }

    @Test fun `viewport boundary and offset invalidate cached geometry and hidden controls restore originals`() {
        val cue = cue()
        val cache = NativeCaptionDrawCache(cue)
        val moved = cache.get(Size(1920f, 1080f), obstacles, 1f)
        val original = cache.get(Size(1920f, 1080f), obstacles, 0f)
        assertNotSame(moved, original)
        assertEquals(cue.images.map { it.y }, original.map { it.offset.y })
        assertTrue(original.all { it.clip == null })
        val smaller = cache.get(Size(960f, 540f), emptyList(), 0f)
        assertEquals(cue.images.map { it.y / 2 }, smaller.map { it.offset.y })
        val below = cache.get(Size(1920f, 1080f), emptyList(), 1f)
        assertEquals(cue.images.map { it.y }, below.map { it.offset.y })
        assertEquals(listOf(650, 500, 600, 100), cache.get(Size(1920f, 1080f), obstacles, 1f).map { it.offset.y })
        assertTrue(NativeCaptionDrawCache(null).get(Size(1920f, 1080f), obstacles, 0.8f).isEmpty())
        assertTrue(NativeCaptionDrawCache(cue.copy(images = emptyList())).get(Size(1920f, 1080f), obstacles, 0.8f).isEmpty())
    }

    @Test fun `full target chain animates together from first frame and reverses to originals`() {
        val cache = NativeCaptionDrawCache(cue())
        listOf(0f, 0.1f, 0.5f, 1f, 0.5f, 0.1f, 0f).forEach { progress ->
            assertEquals(listOf(850, 700, 800).map { it - (200 * progress).toInt() } + 100,
                cache.get(Size(1920f, 1080f), obstacles, progress).map { it.offset.y })
        }
    }

    @Test fun `obstacle and viewport changes invalidate target while scaling both axes`() {
        val cache = NativeCaptionDrawCache(cue())
        val scaled = obstacles.map { Rect(it.left / 2, it.top / 2, it.right / 2, it.bottom / 2) }
        assertEquals(listOf(325, 250, 300, 50),
            cache.get(Size(960f, 540f), scaled, 1f).map { it.offset.y })
        assertEquals(listOf(425, 350, 400, 50),
            cache.get(Size(960f, 540f), listOf(Rect(700f, 400f, 800f, 500f)), 1f).map { it.offset.y })
    }

    @Test fun `one B62 bitmap supports multiple independently shifted groups`() {
        val image = image(0, 0, 1920, 1080).copy(regions = listOf(
            NativeCaptionRegion(100, 850, 100, 50), NativeCaptionRegion(500, 850, 100, 50),
            NativeCaptionRegion(1000, 850, 100, 50)))
        val draws = NativeCaptionDrawCache(cue().copy(images = listOf(image))).get(Size(1920f, 1080f),
            listOf(Rect(100f, 870f, 200f, 1000f), Rect(500f, 820f, 600f, 1000f)), 1f)
        assertEquals(listOf(-30, -80, 0), draws.map { it.offset.y })
        assertTrue(draws.all { it.bitmap === draws.first().bitmap })
    }

    @Test fun `mixed B62 image retains stationary and shifted clips using the same bitmap`() {
        val image = image(0, 0, 1920, 1080).copy(regions = listOf(
            NativeCaptionRegion(100, 850, 800, 150), NativeCaptionRegion(1200, 100, 100, 100)
        ))
        val draws = NativeCaptionDrawCache(cue().copy(images = listOf(image)))
            .get(Size(1920f, 1080f), obstacles, 1f)
        assertEquals(listOf(-200, 0), draws.map { it.offset.y })
        assertSame(draws[0].bitmap, draws[1].bitmap)
        assertNotSame(draws[0].clip, draws[1].clip)
        assertEquals(listOf(1920, 1920), draws.map { it.size.width })
    }
}
