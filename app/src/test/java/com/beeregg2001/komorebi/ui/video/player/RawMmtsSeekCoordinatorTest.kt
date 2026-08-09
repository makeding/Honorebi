package com.beeregg2001.komorebi.ui.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMmtsSeekCoordinatorTest {
    @Test
    fun requestsAreConflatedBeforeTheFirstSeekStarts() {
        val coordinator = RawMmtsSeekCoordinator()

        coordinator.request(10_000L)
        coordinator.request(40_000L)

        assertEquals(40_000L, coordinator.beginNext())
        assertEquals(40_000L, coordinator.pendingPositionMs())
    }

    @Test
    fun activeSeekBlocksAnotherRepositionAndKeepsTheLatestTarget() {
        val coordinator = RawMmtsSeekCoordinator()
        coordinator.request(10_000L)
        assertEquals(10_000L, coordinator.beginNext())

        coordinator.request(20_000L)
        coordinator.request(30_000L)

        assertNull(coordinator.beginNext())
        assertEquals(30_000L, coordinator.pendingPositionMs())
        assertTrue(coordinator.finish(10_000L))
        assertEquals(30_000L, coordinator.beginNext())
    }

    @Test
    fun settledLatestTargetClearsPendingPosition() {
        val coordinator = RawMmtsSeekCoordinator()
        coordinator.request(25_000L)
        assertEquals(25_000L, coordinator.beginNext())

        assertFalse(coordinator.finish(25_000L))
        assertNull(coordinator.pendingPositionMs())
    }

    @Test
    fun pendingSeekTargetWinsWhileThePlayerStillReportsItsOldPosition() {
        assertEquals(
            90_000L,
            resolvePersistablePlaybackPositionMs(
                pendingSeekPositionMs = 90_000L,
                rawPlayerPositionMs = 15_000L,
                fallbackPositionMs = 15_000L,
                isPlayerReady = false,
                isLiveStream = false,
                isRecordingChasePlayback = false,
                playbackOffsetMs = 0L
            )
        )
    }

    @Test
    fun loadingZeroDoesNotReplaceANonZeroResumePosition() {
        assertEquals(
            45_000L,
            resolvePersistablePlaybackPositionMs(
                pendingSeekPositionMs = null,
                rawPlayerPositionMs = 0L,
                fallbackPositionMs = 45_000L,
                isPlayerReady = false,
                isLiveStream = false,
                isRecordingChasePlayback = false,
                playbackOffsetMs = 0L
            )
        )
    }

    @Test
    fun recreatedPlayer_resumesTheRetainedSeekPosition() {
        assertEquals(
            180_000L,
            resolveRecreatedPlayerStartPositionMs(
                explicitResumePositionMs = null,
                isFirstLoad = false,
                retainedPlaybackPositionMs = 180_000L,
            )
        )
        assertNull(
            resolveRecreatedPlayerStartPositionMs(
                explicitResumePositionMs = null,
                isFirstLoad = true,
                retainedPlaybackPositionMs = 180_000L,
            )
        )
        assertEquals(
            90_000L,
            resolveRecreatedPlayerStartPositionMs(
                explicitResumePositionMs = 90_000L,
                isFirstLoad = false,
                retainedPlaybackPositionMs = 180_000L,
            )
        )
    }
}
