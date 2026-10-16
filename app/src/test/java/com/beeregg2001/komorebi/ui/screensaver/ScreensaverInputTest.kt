package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ScreensaverInputTest {
    @Test fun transportDownAndUpAreForwardedOnceWithoutDismissal() {
        var dismissals = 0
        val dispatched = mutableListOf<KeyEvent>()
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)) {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(action, code)
                assertTrue(handleScreensaverKey(event, { dismissals++ }, dispatched::add))
                assertSame(event, dispatched.last())
            }
        }
        assertEquals(0, dismissals)
        assertEquals(16, dispatched.size)
    }

    @Test fun ordinaryDismissalConsumesKeyWithoutForwardingToPlayer() {
        var dismissals = 0
        var media = 0
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(handleScreensaverKey(KeyEvent(action, KeyEvent.KEYCODE_DPAD_CENTER), { dismissals++ }, { media++ }))
        }
        assertEquals(1, dismissals)
        assertEquals(0, media)
    }
}
