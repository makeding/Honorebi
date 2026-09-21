package com.beeregg2001.komorebi.ui.live

import android.app.Application
import android.content.Context
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LiveAudioFocusTest {
    @Test
    fun secondSlotDoesNotStealFirstSlotsFocusAndExternalLossPausesTheGroup() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadow = shadowOf(manager)
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val changes = mutableListOf<Boolean>()
        val focus = LiveAudioFocus(context) { changes += it }

        assertTrue(focus.acquire())
        val firstRequest = shadow.lastAudioFocusRequest
        assertTrue(focus.acquire()) // Start the second picture.
        assertSame(firstRequest, shadow.lastAudioFocusRequest)
        assertNull(shadow.lastAbandonedAudioFocusRequest)
        assertTrue(changes.isEmpty())

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(focus.playbackAllowed)
        focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(focus.playbackAllowed)
        assertEquals(listOf(false, true), changes)
        focus.release()
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
        focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(focus.playbackAllowed)
    }

    @Test
    fun focusDenialDoesNotStartPlaybackAndPermanentLossDoesNotResume() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val focus = LiveAudioFocus(context) {}
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(focus.acquire())
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(focus.acquire())
        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(focus.playbackAllowed)
    }
}
