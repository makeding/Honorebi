package com.beeregg2001.komorebi.ui.screensaver

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.beeregg2001.komorebi.ui.main.AppWakeState
import com.beeregg2001.komorebi.ui.main.rememberAppWakeState
import kotlinx.coroutines.delay

@Stable
internal class InAppScreensaverState {
    var isVisible by mutableStateOf(false)
        internal set

    fun dismiss() {
        isVisible = false
        ScreensaverInteractionTracker.onUserInteraction()
    }
}

/** Idle belongs to a resumed, interactive session, never to time spent asleep. */
@Composable
internal fun rememberInAppScreensaverState(
    enabled: Boolean,
    timeoutMinutes: Int,
    hasImages: Boolean,
    wakeState: AppWakeState = rememberAppWakeState(),
    playerTimeoutMinutes: Int = 10,
): InAppScreensaverState {
    val state = remember { InAppScreensaverState() }
    val lastInteraction by ScreensaverInteractionTracker.lastInteractionMillis.collectAsState()
    val playbackActive by ScreensaverInteractionTracker.playbackActive.collectAsState()
    val pausedPlayback by ScreensaverInteractionTracker.pausedPlayback.collectAsState()
    val paused = pausedPlayback != null
    val hasContent = hasImages || paused
    val timeout = if (paused) playerTimeoutMinutes else timeoutMinutes

    val eligible = wakeState.isAwake
    val idleStart = maxOf(lastInteraction, wakeState.sessionStartedAtMillis)
    LaunchedEffect(enabled, timeout, hasContent, eligible, playbackActive, paused, idleStart) {
        state.isVisible = false
        if (!enabled || !hasContent || !eligible || playbackActive) return@LaunchedEffect
        val deadline = idleStart + timeout.coerceAtLeast(1) * 60_000L
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)
        // Lifecycle/input can change before recomposition cancels this old effect.
        if (wakeState.isAwake &&
            wakeState.sessionStartedAtMillis <= idleStart &&
            !ScreensaverInteractionTracker.playbackActive.value &&
            (ScreensaverInteractionTracker.pausedPlayback.value != null) == paused &&
            ScreensaverInteractionTracker.lastInteractionMillis.value == lastInteraction &&
            SystemClock.elapsedRealtime() >= deadline
        ) state.isVisible = true
    }
    return state
}

/** Release the existing player screen-on hold; the TV owns actual hardware sleep. */
@Composable
internal fun EndedPlaybackScreenHold(minutes: Int, wakeState: AppWakeState) {
    val ended by ScreensaverInteractionTracker.playbackEnded.collectAsState()
    val lastInteraction by ScreensaverInteractionTracker.lastInteractionMillis.collectAsState()
    val awake = wakeState.isAwake
    val idleStart = maxOf(lastInteraction, wakeState.sessionStartedAtMillis)
    LaunchedEffect(ended, minutes, awake, idleStart) {
        ScreensaverInteractionTracker.allowSystemSleep(false)
        if (!ended || !awake) return@LaunchedEffect
        val deadline = idleStart + minutes.coerceAtLeast(0) * 60_000L
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)
        if (wakeState.isAwake && ScreensaverInteractionTracker.playbackEnded.value &&
            ScreensaverInteractionTracker.lastInteractionMillis.value == lastInteraction &&
            wakeState.sessionStartedAtMillis <= idleStart
        ) ScreensaverInteractionTracker.allowSystemSleep(true)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { ScreensaverInteractionTracker.allowSystemSleep(false) }
    }
}
