package com.beeregg2001.komorebi.ui.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileBoundsTest {
    @Test
    fun resolvesTileWithoutAllocatingACroppedBitmap() {
        assertEquals(
            TileBounds(left = 640, top = 180, width = 320, height = 180),
            tileBoundsFor(
                col = 2,
                row = 1,
                tileWidth = 320,
                tileHeight = 180,
                sheetWidth = 1280,
                sheetHeight = 720,
            ),
        )
    }

    @Test
    fun rejectsInvalidOrOutOfBoundsTiles() {
        assertNull(tileBoundsFor(-1, 0, 320, 180, 1280, 720))
        assertNull(tileBoundsFor(4, 0, 320, 180, 1280, 720))
        assertNull(tileBoundsFor(0, 4, 320, 180, 1280, 720))
        assertNull(tileBoundsFor(0, 0, 0, 180, 1280, 720))
    }
}
