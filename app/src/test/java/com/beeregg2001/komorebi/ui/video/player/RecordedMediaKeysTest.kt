package com.beeregg2001.komorebi.ui.video.player

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordedMediaKeysTest {
    private val keys = RecordedMediaKeys()
    private val actions = mutableListOf<RecordedMediaKeys.Action>()

    private fun event(key: Int, time: Long = 1000, repeat: Int = 0, up: Boolean = false,
                      downTime: Long = 1000, flags: Int = 0) = KeyEvent(
        downTime, time, if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN,
        key, repeat, 0, 0, 0, flags
    )

    private fun send(event: KeyEvent, chapters: Boolean = true) = keys.handle(event, chapters, actions::add)

    @Test fun fastForwardRepeatsAtMostOncePer400msAndDoesNotFireOnRelease() {
        val key = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        send(event(key))
        send(event(key, 1399, 1))
        send(event(key, 1400, 2))
        send(event(key, 1500, 3))
        send(event(key, 1800, 4))
        send(event(key, 1900, up = true))
        assertEquals(listOf(RecordedMediaKeys.Action.FORWARD, RecordedMediaKeys.Action.NEXT,
            RecordedMediaKeys.Action.NEXT), actions)
    }

    @Test fun noChaptersRepeatsTimeSeekAndDedicatedChapterKeysIgnoreRepeats() {
        send(event(KeyEvent.KEYCODE_MEDIA_REWIND), false)
        send(event(KeyEvent.KEYCODE_MEDIA_REWIND, 1400, 1), false)
        send(event(KeyEvent.KEYCODE_MEDIA_NEXT, 1500))
        send(event(KeyEvent.KEYCODE_MEDIA_NEXT, 2000, 1))
        assertEquals(listOf(RecordedMediaKeys.Action.BACK, RecordedMediaKeys.Action.BACK,
            RecordedMediaKeys.Action.NEXT), actions)
    }

    @Test fun cancellationInterruptionAndOrphanReleasesDoNotTogglePlayback() {
        val key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        send(event(key))
        send(event(key, up = true, flags = KeyEvent.FLAG_CANCELED))
        send(event(key, up = true))
        send(event(key))
        assertFalse(send(event(KeyEvent.KEYCODE_DPAD_RIGHT)))
        send(event(key, up = true))
        send(event(key))
        keys.reset()
        send(event(key, up = true))
        assertTrue(actions.isEmpty())
    }

    @Test fun heldTransportKeyDispatchesOnlyOnceAndOldKeyReleaseCannotResetNewGesture() {
        val key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        send(event(key))
        send(event(key, 1400, 1))
        send(event(KeyEvent.KEYCODE_MEDIA_PAUSE, 1500, downTime = 1500))
        send(event(key, 1600, up = true))
        send(event(KeyEvent.KEYCODE_MEDIA_PAUSE, 1700, up = true, downTime = 1500))
        assertEquals(listOf(RecordedMediaKeys.Action.PAUSE), actions)
    }

    @Test fun playerDispatchIsIdempotentClampsSeekAndYieldsToOverlayAndPip() {
        val state = VideoPlayerState()
        var playing = true
        var plays = 0
        var pauses = 0
        var position = 95_000L
        var previous = 0
        var next = 0
        fun dispatch(native: KeyEvent, overlay: Boolean = false, pip: Boolean = false) = state.handleKeyEvent(
            keyEvent = ComposeKeyEvent(native), isPiPMode = pip, isModern = true,
            showControls = false, isSubOverlayOpen = overlay, chapters = emptyList(),
            canOpenSceneSearch = false, totalDurationMs = 100_000L,
            getCurrentPositionMs = { position }, performSeek = { position = it }, triggerSeekingPreview = {},
            onShowControlsChange = {}, onPiPRequested = {}, onBackPressed = {}, onSceneSearchToggle = {},
            onSettingsMenuToggle = {}, onChapterListToggle = {}, onSubMenuToggle = {},
            exoPlayerIsPlaying = playing, onPause = { pauses++; playing = false },
            onPlay = { plays++; playing = true }, onSkipPreviousChapter = { previous++ },
            onSkipNextChapter = { next++ }
        )
        fun press(key: Int) { assertTrue(dispatch(event(key))); assertTrue(dispatch(event(key, up = true))) }
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertEquals(0, plays)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertEquals(1, pauses)
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals(1, plays)
        assertEquals(2, pauses)
        press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        assertEquals(100_000L, position)
        position = 5_000L
        press(KeyEvent.KEYCODE_MEDIA_REWIND)
        assertEquals(0L, position)
        press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        press(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(1, previous)
        assertEquals(1, next)
        assertFalse(dispatch(event(KeyEvent.KEYCODE_MEDIA_PLAY), overlay = true))
        dispatch(event(KeyEvent.KEYCODE_MEDIA_PLAY, up = true))
        assertFalse(dispatch(event(KeyEvent.KEYCODE_MEDIA_PLAY), pip = true))
        dispatch(event(KeyEvent.KEYCODE_MEDIA_PLAY, up = true))
        assertEquals(1, plays)
    }
}
