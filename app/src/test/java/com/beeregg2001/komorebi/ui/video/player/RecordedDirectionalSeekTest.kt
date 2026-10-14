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
class RecordedDirectionalSeekTest {
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT
    private val left = KeyEvent.KEYCODE_DPAD_LEFT
    private fun event(key: Int = right, time: Long = 1000L, down: Long = 1000L,
                      repeat: Int = 0, up: Boolean = false, flags: Int = 0) = KeyEvent(
        down, time, if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN,
        key, repeat, 0, 0, 0, flags,
    )
    private fun send(gesture: RecordedDirectionalSeek, event: KeyEvent,
                     commits: MutableList<Long>, duration: Long = 1_800_000L) =
        gesture.handle(event, 120_000L, duration, 10_000L, commits::add)

    @Test fun shortPressPreviewsImmediatelyAndCommitsOnceOnRelease() {
        val gesture = RecordedDirectionalSeek()
        val commits = mutableListOf<Long>()
        send(gesture, event(), commits)
        assertEquals(130_000L, gesture.previewPositionMs)
        assertTrue(commits.isEmpty())
        send(gesture, event(time = 1200L, up = true), commits)
        gesture.advance(10_000L, 1_800_000L)
        send(gesture, event(time = 1300L, up = true), commits)
        assertEquals(listOf(130_000L), commits)
        assertFalse(gesture.isHolding)
        assertNull(gesture.previewPositionMs)
    }

    @Test fun holdJumpsInIncreasingTiersRegardlessOfRepeatsOrFrameFrequency() {
        val sparse = RecordedDirectionalSeek()
        val dense = RecordedDirectionalSeek()
        val commits = mutableListOf<Long>()
        send(sparse, event(), commits, duration = 7_200_000L)
        send(dense, event(), commits, duration = 7_200_000L)
        for (time in 1400L..5000L step 16L) {
            dense.advance(time, 7_200_000L)
            send(dense, event(time = time, repeat = 999), commits, duration = 7_200_000L)
        }
        sparse.advance(5000L, 7_200_000L)
        dense.advance(5000L, 7_200_000L)
        assertEquals(sparse.previewPositionMs, dense.previewPositionMs)
        val atFour = sparse.previewPositionMs!!
        sparse.advance(6000L, 7_200_000L)
        assertEquals(4 * 300_000L, sparse.previewPositionMs!! - atFour)
        assertTrue(commits.isEmpty())
    }

    @Test fun previewStaysStillBetweenJumpsAndEachTierIncreasesTheStep() {
        val gesture = RecordedDirectionalSeek()
        val commits = mutableListOf<Long>()
        send(gesture, event(), commits, duration = 7_200_000L)
        var previous = gesture.previewPositionMs!!
        for ((time, expectedStep) in listOf(
            1400L to 10_000L, 1650L to 10_000L, 1900L to 10_000L,
            2150L to 30_000L, 2400L to 30_000L, 2650L to 30_000L, 2900L to 30_000L,
            3150L to 60_000L, 3400L to 60_000L, 3650L to 60_000L, 3900L to 60_000L,
            4150L to 120_000L, 4400L to 120_000L, 4650L to 120_000L, 4900L to 120_000L,
            5150L to 300_000L,
        )) {
            gesture.advance(time - 1L, 7_200_000L)
            assertEquals(previous, gesture.previewPositionMs)
            gesture.advance(time, 7_200_000L)
            assertEquals(expectedStep, gesture.previewPositionMs!! - previous)
            previous = gesture.previewPositionMs!!
        }
    }

    @Test fun longRecordingsCanRewindToStartWithinSeconds() {
        for ((duration, holdMs) in listOf(1_800_000L to 4_900L, 7_200_000L to 9_400L)) {
            val gesture = RecordedDirectionalSeek()
            val commits = mutableListOf<Long>()
            gesture.handle(event(left), duration, duration, 10_000L, commits::add)
            gesture.advance(1000L + holdMs, duration)
            assertEquals(0L, gesture.previewPositionMs)
            assertTrue(commits.isEmpty())
            gesture.handle(event(left, time = 1000L + holdMs, up = true), duration, duration, 10_000L, commits::add)
            assertEquals(listOf(0L), commits)
        }
    }

    @Test fun reversalResetsStepTierAndIgnoresOldDirectionRelease() {
        val gesture = RecordedDirectionalSeek()
        val commits = mutableListOf<Long>()
        send(gesture, event(), commits)
        gesture.advance(5000L, 1_800_000L)
        val fastTarget = gesture.previewPositionMs!!
        send(gesture, event(left, 5000L, 5000L), commits)
        assertEquals(fastTarget - 10_000L, gesture.previewPositionMs)
        send(gesture, event(time = 5100L, up = true), commits)
        send(gesture, event(time = 5100L, up = true, flags = KeyEvent.FLAG_CANCELED), commits)
        assertTrue(gesture.isHolding)
        gesture.advance(5300L, 1_800_000L)
        assertEquals(fastTarget - 10_000L, gesture.previewPositionMs)
        send(gesture, event(left, 5300L, 5000L, up = true), commits)
        assertEquals(listOf(fastTarget - 10_000L), commits)
    }

    @Test fun clampsEndpointsAndDoesNotAcceptUnknownDurationOrCanceledOrOrphanEvents() {
        val gesture = RecordedDirectionalSeek()
        val commits = mutableListOf<Long>()
        send(gesture, event(), commits, duration = 0L)
        assertFalse(gesture.isHolding)
        send(gesture, event(), commits, duration = 125_000L)
        gesture.advance(10_000L, 125_000L)
        assertEquals(125_000L, gesture.previewPositionMs)
        send(gesture, event(time = 10_000L, up = true, flags = KeyEvent.FLAG_CANCELED), commits)
        send(gesture, event(time = 11_000L, repeat = 10), commits)
        send(gesture, event(time = 11_000L, up = true), commits)
        assertNull(gesture.previewPositionMs)
        assertTrue(commits.isEmpty())
        send(gesture, event(left), commits)
        gesture.advance(10_000L, 1_800_000L)
        assertEquals(0L, gesture.previewPositionMs)
        gesture.cancel()
        gesture.advance(20_000L, 1_800_000L)
        assertFalse(gesture.isHolding)
        assertNull(gesture.previewPositionMs)
    }

    @Test fun rootRoutesTrackQuickSeekAndNavigationAndCancelsBeforeOtherActions() {
        val state = VideoPlayerState()
        val commits = mutableListOf<Long>()
        fun dispatch(event: KeyEvent, show: Boolean = true, modern: Boolean = true,
                     overlay: Boolean = false, pip: Boolean = false) = state.handleKeyEvent(
            keyEvent = ComposeKeyEvent(event), isPiPMode = pip, isModern = modern,
            showControls = show, isSubOverlayOpen = overlay, chapters = emptyList(),
            canOpenSceneSearch = false, totalDurationMs = 1_800_000L,
            getCurrentPositionMs = { 120_000L }, performSeek = commits::add,
            triggerSeekingPreview = {}, onShowControlsChange = {}, onPiPRequested = {},
            onBackPressed = {}, onSceneSearchToggle = {}, onSettingsMenuToggle = {},
            onChapterListToggle = {}, onSubMenuToggle = {}, exoPlayerIsPlaying = true,
            onPause = { fail("Seek must preserve playback") }, onPlay = { fail("Seek must preserve playback") },
            onSkipPreviousChapter = {}, onSkipNextChapter = {},
        )
        assertFalse(dispatch(event())) // Visible buttons keep horizontal focus navigation.
        state.isSeekBarFocused = true
        assertTrue(dispatch(event()))
        assertEquals(130_000L, state.directionalSeek.previewPositionMs)
        assertTrue(dispatch(event(time = 1200L, up = true)))
        assertEquals(listOf(130_000L), commits)
        state.isSeekBarFocused = false
        assertTrue(dispatch(event(), show = false))
        assertEquals(150_000L, state.directionalSeek.previewPositionMs)
        dispatch(event(KeyEvent.KEYCODE_BACK, time = 1100L))
        dispatch(event(time = 1200L, up = true))
        assertEquals(1, commits.size)
        dispatch(event(), modern = false)
        dispatch(event(repeat = 1), overlay = true)
        assertFalse(state.directionalSeek.isHolding)
        dispatch(event(), modern = false)
        dispatch(event(repeat = 1), pip = true)
        assertFalse(state.directionalSeek.isHolding)
    }
}
