package com.beeregg2001.komorebi.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackServiceStateMachineTest {

    @Test
    fun stopWhileStartingDefersStopUntilForegroundStarts() {
        val sm = BackgroundPlaybackServiceStateMachine()
        val initial = BackgroundPlaybackNotificationState(title = "NHK", subtitle = "News", isPlaying = true)

        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = initial),
        )
        assertTrue(sm.isAwaitingForegroundStart())

        // Stopping before onStartCommand() runs MUST NOT emit STOP_SERVICE (would crash with FGS timeout).
        assertEquals(
            BackgroundPlaybackSyncAction.NONE,
            sm.onSyncRequested(active = false, state = BackgroundPlaybackNotificationState()),
        )
        assertTrue(sm.isAwaitingForegroundStart())

        // Once onStartCommand() calls startForeground(), state machine tells service to stop immediately.
        assertTrue(sm.onForegroundStarted(initial))
        assertFalse(sm.isAwaitingForegroundStart())

        // Subsequent active=true starts cleanly again.
        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = initial),
        )
    }

    @Test
    fun reactivateWhilePendingStopCancelsPendingStopWithoutDuplicateStartForegroundService() {
        val sm = BackgroundPlaybackServiceStateMachine()
        val first = BackgroundPlaybackNotificationState(title = "Ch1", subtitle = "Prog1", isPlaying = true)
        val second = BackgroundPlaybackNotificationState(title = "Ch2", subtitle = "Prog2", isPlaying = true)

        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = first),
        )
        assertEquals(
            BackgroundPlaybackSyncAction.NONE,
            sm.onSyncRequested(active = false, state = BackgroundPlaybackNotificationState()),
        )
        assertEquals(
            BackgroundPlaybackSyncAction.NONE,
            sm.onSyncRequested(active = true, state = second),
        )
        assertEquals(second, sm.currentDesiredState())

        // onStartCommand() runs with the latest state and stays in foreground.
        assertFalse(sm.onForegroundStarted(second))
        assertFalse(sm.isAwaitingForegroundStart())
    }

    @Test
    fun updatesWhileInForegroundUseNotificationUpdateInsteadOfStartForegroundService() {
        val sm = BackgroundPlaybackServiceStateMachine()
        val playing = BackgroundPlaybackNotificationState(title = "NHK", subtitle = "News", isPlaying = true)
        val paused = playing.copy(isPlaying = false)

        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = playing),
        )
        assertFalse(sm.onForegroundStarted(playing))

        // Identical state is a no-op.
        assertEquals(
            BackgroundPlaybackSyncAction.NONE,
            sm.onSyncRequested(active = true, state = playing),
        )

        // Changed state updates notification in-place rather than calling startForegroundService().
        assertEquals(
            BackgroundPlaybackSyncAction.UPDATE_NOTIFICATION,
            sm.onSyncRequested(active = true, state = paused),
        )

        // Stopping once already in foreground stops immediately.
        assertEquals(
            BackgroundPlaybackSyncAction.STOP_SERVICE,
            sm.onSyncRequested(active = false, state = BackgroundPlaybackNotificationState()),
        )
    }

    @Test
    fun startForegroundServiceFailureResetsToIdle() {
        val sm = BackgroundPlaybackServiceStateMachine()
        val state = BackgroundPlaybackNotificationState(title = "NHK", subtitle = "News", isPlaying = true)

        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = state),
        )
        sm.onStartForegroundServiceFailed()
        assertFalse(sm.isAwaitingForegroundStart())

        assertEquals(
            BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE,
            sm.onSyncRequested(active = true, state = state),
        )
    }
}
