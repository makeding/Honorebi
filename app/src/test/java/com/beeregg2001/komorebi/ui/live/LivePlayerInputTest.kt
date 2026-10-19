package com.beeregg2001.komorebi.ui.live

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.input.key.KeyEvent
import com.beeregg2001.komorebi.data.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class LivePlayerInputTest {
    private val channel = Channel("gr1", "gr011", "Test", "011", type = "GR",
        isWatchable = true, isDisplay = true, programPresent = null, programFollowing = null)
    private val state = LivePlayerState(RuntimeEnvironment.getApplication())
    private var overlay = false
    private var manual = false
    private var pinned = false
    private var menu = false
    private var browser = false
    private var channelSelections = 0
    private var exits = 0

    private fun key(code: Int, repeat: Int = 0, action: Int = AndroidKeyEvent.ACTION_DOWN): Boolean =
        state.handleKeyEvent(
            KeyEvent(AndroidKeyEvent(0, 0, action, code, repeat)), menu, browser, overlay, manual, pinned,
            channel, mapOf("GR" to listOf(channel)), ScrollState(0), CoroutineScope(Job().apply { cancel() }),
            { channelSelections++ }, { overlay = it }, { manual = it }, { pinned = it },
            { menu = it }, { browser = it }, {}, {}, { exits++ },
        )

    @Test fun downShowsUiThenOpensBrowserWithoutLongPressSkippingSteps() {
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(overlay)
        assertFalse(manual)
        assertFalse(browser)
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat = 1))
        assertFalse(browser)
        key(AndroidKeyEvent.KEYCODE_DPAD_DOWN, action = AndroidKeyEvent.ACTION_UP)
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(browser)
        assertFalse(overlay)
        assertEquals(0, channelSelections)
    }

    @Test fun upUsesTheSameTwoStepsForMenu() {
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_UP))
        assertTrue(overlay)
        assertFalse(menu)
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_UP, repeat = 1))
        assertFalse(menu)
        assertTrue(key(AndroidKeyEvent.KEYCODE_DPAD_UP))
        assertTrue(menu)
        assertFalse(overlay)
    }

    @Test fun backClosesFirstFeedbackWithoutExitingPlayback() {
        key(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        key(AndroidKeyEvent.KEYCODE_BACK)
        key(AndroidKeyEvent.KEYCODE_BACK, action = AndroidKeyEvent.ACTION_UP)
        assertFalse(overlay)
        assertEquals(0, exits)
    }

    @Test fun manualDetailsAndSpecialModesKeepTheirInputOwnership() {
        overlay = true
        manual = true
        key(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(browser)
        assertTrue(manual)
        overlay = false
        manual = false
        state.isDualDisplayMode = true
        key(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(browser)
        assertFalse(overlay)
        browser = false
        state.isDualDisplayMode = false
        state.playerError = "Error"
        assertFalse(key(AndroidKeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(overlay)
    }
}
