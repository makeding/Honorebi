package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val RECORDED_CONTROLS_DISPLAY_POLL_MS = 250L

/** The only progress state consumed by the visual time/track portion of the controls. */
internal data class RecordedControlsDisplayProgress(
    val positionMs: Long,
    val bufferedPositionMs: Long,
)

internal fun shouldPollRecordedControlsDisplay(isVisible: Boolean): Boolean = isVisible

internal fun readRecordedControlsDisplayProgress(
    isVisible: Boolean,
    previous: RecordedControlsDisplayProgress,
    positionMs: () -> Long,
    bufferedPositionMs: () -> Long,
): RecordedControlsDisplayProgress = if (!shouldPollRecordedControlsDisplay(isVisible)) {
    previous
} else {
    RecordedControlsDisplayProgress(
        positionMs = positionMs().coerceAtLeast(0L),
        bufferedPositionMs = bufferedPositionMs().coerceAtLeast(0L),
    )
}

/**
 * Keeps 4 Hz display-only progress out of VideoPlayerScreen and its overlays.
 * Playback automation continues to read ExoPlayer directly in its own effects.
 */
@Composable
internal fun rememberRecordedControlsDisplayProgress(
    isVisible: Boolean,
    initialPositionMs: Long,
    initialBufferedPositionMs: Long,
    positionMs: () -> Long,
    bufferedPositionMs: () -> Long,
    previewPositionMs: () -> Long? = { null },
): State<RecordedControlsDisplayProgress> {
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentBufferedPositionMs by rememberUpdatedState(bufferedPositionMs)
    val currentPreviewPositionMs by rememberUpdatedState(previewPositionMs)
    val progress = remember {
        mutableStateOf(
            RecordedControlsDisplayProgress(
                initialPositionMs.coerceAtLeast(0L),
                initialBufferedPositionMs.coerceAtLeast(0L),
            )
        )
    }

    androidx.compose.runtime.LaunchedEffect(isVisible) {
        if (!shouldPollRecordedControlsDisplay(isVisible)) return@LaunchedEffect
        while (currentCoroutineContext().isActive) {
            val next = readRecordedControlsDisplayProgress(
                isVisible = true,
                previous = progress.value,
                positionMs = currentPositionMs,
                bufferedPositionMs = currentBufferedPositionMs,
            )
            if (next != progress.value) progress.value = next
            delay(RECORDED_CONTROLS_DISPLAY_POLL_MS)
        }
    }
    androidx.compose.runtime.LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        snapshotFlow { currentPreviewPositionMs() != null }.collect { previewActive ->
            // On commit/cancel, refresh immediately so the track never flashes its old 4Hz snapshot.
            if (!previewActive) progress.value = readRecordedControlsDisplayProgress(
                true, progress.value, currentPositionMs, currentBufferedPositionMs,
            )
        }
    }
    return progress
}
