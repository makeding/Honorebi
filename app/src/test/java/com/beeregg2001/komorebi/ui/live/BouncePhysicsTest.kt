package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BouncePhysicsTest {

    @Test
    fun staysInBoundsOverTenThousandRandomSteps() {
        val physics = BouncePhysics(paletteSize = DVD_BOUNCE_PALETTE.size, seed = 42L)
        val containerW = 1920f
        val containerH = 1080f
        val logoW = 212f
        val logoH = 104f
        val inset = 16f
        physics.reset(
            containerW = containerW,
            containerH = containerH,
            logoW = logoW,
            logoH = logoH,
            vxPxPerSec = BouncePhysics.DVD_VX_DP_PER_SEC,
            vyPxPerSec = BouncePhysics.DVD_VY_DP_PER_SEC,
            insetPx = inset,
        )

        val rng = Random(1234L)
        val minX = inset
        val maxX = containerW - logoW - inset
        val minY = inset
        val maxY = containerH - logoH - inset

        repeat(10_000) {
            val dtNanos = rng.nextLong(1_000_000L, 120_000_000L)
            val prevColor = physics.colorIndex
            val bounced = physics.step(dtNanos)
            assertTrue("x=${physics.x} out of [$minX, $maxX]", physics.x in minX..maxX)
            assertTrue("y=${physics.y} out of [$minY, $maxY]", physics.y in minY..maxY)
            if (bounced) {
                assertNotEquals("color must change on bounce", prevColor, physics.colorIndex)
            } else {
                assertEquals("color must stay unchanged without bounce", prevColor, physics.colorIndex)
            }
        }
    }

    @Test
    fun reflectsOnEachEdge() {
        // Right edge
        val right = BouncePhysics(paletteSize = 4, seed = 1L).apply {
            reset(1000f, 600f, 100f, 50f, vxPxPerSec = 200f, vyPxPerSec = 0f, insetPx = 10f, startX = 888f, startY = 200f)
        }
        assertTrue(right.step(30_000_000L))
        assertTrue("vx should flip negative on right wall", right.vx < 0f)
        assertTrue(right.x <= 890f)

        // Left edge
        val left = BouncePhysics(paletteSize = 4, seed = 1L).apply {
            reset(1000f, 600f, 100f, 50f, vxPxPerSec = -200f, vyPxPerSec = 0f, insetPx = 10f, startX = 12f, startY = 200f)
        }
        assertTrue(left.step(30_000_000L))
        assertTrue("vx should flip positive on left wall", left.vx > 0f)
        assertTrue(left.x >= 10f)

        // Bottom edge
        val bottom = BouncePhysics(paletteSize = 4, seed = 1L).apply {
            reset(1000f, 600f, 100f, 50f, vxPxPerSec = 0f, vyPxPerSec = 200f, insetPx = 10f, startX = 200f, startY = 538f)
        }
        assertTrue(bottom.step(30_000_000L))
        assertTrue("vy should flip negative on bottom wall", bottom.vy < 0f)
        assertTrue(bottom.y <= 540f)

        // Top edge
        val top = BouncePhysics(paletteSize = 4, seed = 1L).apply {
            reset(1000f, 600f, 100f, 50f, vxPxPerSec = 0f, vyPxPerSec = -200f, insetPx = 10f, startX = 200f, startY = 12f)
        }
        assertTrue(top.step(30_000_000L))
        assertTrue("vy should flip positive on top wall", top.vy > 0f)
        assertTrue(top.y >= 10f)
    }

    @Test
    fun cornerHitCountsAsSingleBounceAndAdvancesColorOnce() {
        val physics = BouncePhysics(paletteSize = 6, seed = 7L)
        physics.reset(
            containerW = 500f,
            containerH = 400f,
            logoW = 100f,
            logoH = 80f,
            vxPxPerSec = 200f,
            vyPxPerSec = 200f,
            insetPx = 10f,
            startX = 389f,
            startY = 309f,
        )
        val initialColor = physics.colorIndex
        val bounced = physics.step(20_000_000L) // +4px in both axes -> hits both right(390) and bottom(310)
        assertTrue(bounced)
        assertTrue(physics.vx < 0f)
        assertTrue(physics.vy < 0f)
        assertEquals((initialColor + 1) % 6, physics.colorIndex)
    }

    @Test
    fun dtIsClampedSoTwoSecondGapDoesNotTeleport() {
        val physics = BouncePhysics(paletteSize = 8, seed = 9L)
        physics.reset(
            containerW = 1920f,
            containerH = 1080f,
            logoW = 200f,
            logoH = 100f,
            vxPxPerSec = 100f,
            vyPxPerSec = 80f,
            insetPx = 16f,
            startX = 500f,
            startY = 400f,
        )
        val bounced = physics.step(2_000_000_000L) // 2 seconds -> clamped to 50ms
        assertFalse(bounced)
        assertEquals(505f, physics.x, 0.01f)
        assertEquals(404f, physics.y, 0.01f)
    }

    @Test
    fun resizeClampsPositionIntoNewBounds() {
        val physics = BouncePhysics(paletteSize = 8, seed = 11L)
        physics.reset(
            containerW = 1920f,
            containerH = 1080f,
            logoW = 200f,
            logoH = 100f,
            vxPxPerSec = 100f,
            vyPxPerSec = 80f,
            insetPx = 16f,
            startX = 1600f,
            startY = 900f,
        )
        physics.resize(containerW = 800f, containerH = 450f, logoW = 200f, logoH = 100f, insetPx = 16f)
        assertEquals(800f - 200f - 16f, physics.x, 0.01f)
        assertEquals(450f - 100f - 16f, physics.y, 0.01f)
    }
}
