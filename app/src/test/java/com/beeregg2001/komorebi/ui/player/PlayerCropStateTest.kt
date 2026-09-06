package com.beeregg2001.komorebi.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCropStateTest {
    @Test
    fun `live direct adjustment preserves two point steps and center zoom cycle`() {
        val crop = PlayerCropState().apply { mode = PlayerCropMode.DIRECT_ADJUST }

        assertTrue(crop.handleDirectAdjustKey(KeyEvent.KEYCODE_DPAD_UP, true, LivePlayerCropInputProfile))
        assertEquals(-2f, crop.yPercent)
        assertTrue(crop.handleDirectAdjustKey(KeyEvent.KEYCODE_DPAD_CENTER, true, LivePlayerCropInputProfile))
        assertEquals(125f, crop.zoomPercent)
        assertFalse(crop.handleDirectAdjustKey(KeyEvent.KEYCODE_MEDIA_PLAY, true, LivePlayerCropInputProfile))
    }

    @Test
    fun `recorded direct adjustment clamps positions and returns to the menu`() {
        val crop = PlayerCropState().apply {
            mode = PlayerCropMode.DIRECT_ADJUST
            xPercent = 99f
            zoomPercent = 198f
        }
        var returnedToMenu = false

        assertTrue(crop.handleDirectAdjustKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, RecordedPlayerCropInputProfile))
        assertEquals(100f, crop.xPercent)
        assertTrue(crop.handleDirectAdjustKey(KeyEvent.KEYCODE_PAGE_UP, true, RecordedPlayerCropInputProfile))
        assertEquals(200f, crop.zoomPercent)
        assertTrue(crop.handleDirectAdjustKey(
            KeyEvent.KEYCODE_BACK,
            true,
            RecordedPlayerCropInputProfile
        ) { returnedToMenu = true })
        assertEquals(PlayerCropMode.MENU, crop.mode)
        assertTrue(returnedToMenu)
    }
}
