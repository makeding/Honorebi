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
): InAppScreensaverState {
    val state = remember { InAppScreensaverState() }
    val lastInteraction by ScreensaverInteractionTracker.lastInteractionMillis.collectAsState()
    val playbackActive by ScreensaverInteractionTracker.playbackActive.collectAsState()

    val eligible = wakeState.isAwake
    val idleStart = maxOf(lastInteraction, wakeState.sessionStartedAtMillis)
    LaunchedEffect(enabled, timeoutMinutes, hasImages, eligible, playbackActive, idleStart) {
        state.isVisible = false
        if (!enabled || !hasImages || !eligible || playbackActive) return@LaunchedEffect
        val deadline = idleStart + timeoutMinutes.coerceAtLeast(1) * 60_000L
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)
        // Lifecycle/input can change before recomposition cancels this old effect.
        if (wakeState.isAwake &&
            wakeState.sessionStartedAtMillis <= idleStart &&
            !ScreensaverInteractionTracker.playbackActive.value &&
            ScreensaverInteractionTracker.lastInteractionMillis.value == lastInteraction &&
            SystemClock.elapsedRealtime() >= deadline
        ) state.isVisible = true
    }
    return state
}
