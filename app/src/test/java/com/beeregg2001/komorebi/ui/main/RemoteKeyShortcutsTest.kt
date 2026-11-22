package com.beeregg2001.komorebi.ui.main

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyShortcutsTest {

    private fun baseContext(
        isInOsPip: Boolean = false,
        isSettingsOpen: Boolean = false,
        isAiOverlayOpen: Boolean = false,
        hasRootOverlay: Boolean = false,
        isLivePlaybackFullscreen: Boolean = false,
        isTifPassthrough: Boolean = false,
        isRecordedPlaybackFullscreen: Boolean = false,
    ) = RemoteKeyContext(
        isInOsPip = isInOsPip,
        isSettingsOpen = isSettingsOpen,
        isAiOverlayOpen = isAiOverlayOpen,
        hasRootOverlay = hasRootOverlay,
        isLivePlaybackFullscreen = isLivePlaybackFullscreen,
        isTifPassthrough = isTifPassthrough,
        isRecordedPlaybackFullscreen = isRecordedPlaybackFullscreen,
    )

    @Test
    fun shortcutFor_mapsGuideDvrTvAndIgnoresDataServiceAndOthers() {
        assertEquals(RemoteShortcut.GUIDE, RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_GUIDE))
        assertEquals(RemoteShortcut.DVR, RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_DVR))
        assertEquals(RemoteShortcut.TV, RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_TV))
        assertNull(RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_TV_DATA_SERVICE))
        assertNull(RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_HOME))
        assertNull(RemoteKeyShortcuts.shortcutFor(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun digitFor_mapsRowAndNumpadDigits() {
        val rowKeys = listOf(
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        )
        val numpadKeys = listOf(
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9,
        )
        rowKeys.forEachIndexed { index, keyCode ->
            assertEquals(('0'.code + index).toChar(), RemoteKeyShortcuts.digitFor(keyCode))
        }
        numpadKeys.forEachIndexed { index, keyCode ->
            assertEquals(('0'.code + index).toChar(), RemoteKeyShortcuts.digitFor(keyCode))
        }
        assertNull(RemoteKeyShortcuts.digitFor(KeyEvent.KEYCODE_A))
        assertNull(RemoteKeyShortcuts.digitFor(KeyEvent.KEYCODE_GUIDE))
    }

    @Test
    fun shortcutsAllowed_blocksOnlyOsPipAndAiOverlay() {
        assertTrue(RemoteKeyShortcuts.shortcutsAllowed(baseContext()))
        assertTrue(RemoteKeyShortcuts.shortcutsAllowed(baseContext(isSettingsOpen = true)))
        assertTrue(RemoteKeyShortcuts.shortcutsAllowed(baseContext(hasRootOverlay = true)))
        assertTrue(RemoteKeyShortcuts.shortcutsAllowed(baseContext(isLivePlaybackFullscreen = true)))
        assertTrue(RemoteKeyShortcuts.shortcutsAllowed(baseContext(isRecordedPlaybackFullscreen = true)))
        assertFalse(RemoteKeyShortcuts.shortcutsAllowed(baseContext(isInOsPip = true)))
        assertFalse(RemoteKeyShortcuts.shortcutsAllowed(baseContext(isAiOverlayOpen = true)))
    }

    @Test
    fun numberTuningAllowed_enforcesScopeMatrix() {
        // Launcher idle -> allowed
        assertTrue(RemoteKeyShortcuts.numberTuningAllowed(baseContext()))
        // Fullscreen live (non-passthrough) -> allowed
        assertTrue(RemoteKeyShortcuts.numberTuningAllowed(baseContext(isLivePlaybackFullscreen = true)))
        // Fullscreen live TIF passthrough -> blocked
        assertFalse(
            RemoteKeyShortcuts.numberTuningAllowed(
                baseContext(isLivePlaybackFullscreen = true, isTifPassthrough = true),
            ),
        )
        // Fullscreen recorded -> blocked
        assertFalse(RemoteKeyShortcuts.numberTuningAllowed(baseContext(isRecordedPlaybackFullscreen = true)))
        // Root overlay in launcher -> blocked
        assertFalse(RemoteKeyShortcuts.numberTuningAllowed(baseContext(hasRootOverlay = true)))
        // Settings / AI / OS PiP -> always blocked (even with live playback)
        assertFalse(RemoteKeyShortcuts.numberTuningAllowed(baseContext(isSettingsOpen = true)))
        assertFalse(
            RemoteKeyShortcuts.numberTuningAllowed(
                baseContext(isLivePlaybackFullscreen = true, isSettingsOpen = true),
            ),
        )
        assertFalse(RemoteKeyShortcuts.numberTuningAllowed(baseContext(isAiOverlayOpen = true)))
        assertFalse(
            RemoteKeyShortcuts.numberTuningAllowed(
                baseContext(isLivePlaybackFullscreen = true, isAiOverlayOpen = true),
            ),
        )
        assertFalse(RemoteKeyShortcuts.numberTuningAllowed(baseContext(isInOsPip = true)))
        assertFalse(
            RemoteKeyShortcuts.numberTuningAllowed(
                baseContext(isLivePlaybackFullscreen = true, isInOsPip = true),
            ),
        )
    }

    @Test
    fun appendAndCommitDelay_respectThreeDigitCapAndDelays() {
        assertEquals("0", RemoteKeyShortcuts.append("", '0'))
        assertEquals("01", RemoteKeyShortcuts.append("0", '1'))
        assertEquals("011", RemoteKeyShortcuts.append("01", '1'))
        // 4th digit resets buffer to that digit
        assertEquals("2", RemoteKeyShortcuts.append("011", '2'))
        // Non-digit ignored
        assertEquals("01", RemoteKeyShortcuts.append("01", 'a'))

        assertEquals(RemoteKeyShortcuts.COMMIT_DELAY_MS, RemoteKeyShortcuts.commitDelayMs("1"))
        assertEquals(RemoteKeyShortcuts.COMMIT_DELAY_MS, RemoteKeyShortcuts.commitDelayMs("12"))
        assertEquals(RemoteKeyShortcuts.COMMIT_DELAY_FULL_MS, RemoteKeyShortcuts.commitDelayMs("011"))
    }
}
