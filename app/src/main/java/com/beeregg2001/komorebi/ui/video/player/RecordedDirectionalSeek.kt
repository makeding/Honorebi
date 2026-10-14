package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Preview owner. Only a matching, uncanceled key-up commits a media seek. */
@Stable
internal class RecordedDirectionalSeek {
    var previewPositionMs by mutableStateOf<Long?>(null)
        private set
    var isHolding by mutableStateOf(false)
        private set
    private var activeKey = 0
    private var downTimeMs = 0L
    private var anchorTimeMs = 0L
    private var anchorPositionMs = 0L
    private var lastSampleMs = 0L
    private var initialStepMs = 10_000L

    fun cancel() {
        isHolding = false
        previewPositionMs = null
    }

    fun handle(event: KeyEvent, positionMs: Long, durationMs: Long, stepMs: Long, commit: (Long) -> Unit) {
        if (durationMs <= 0L) { cancel(); return }
        if (event.isCanceled) {
            if (activeKey == event.keyCode && downTimeMs == event.downTime) cancel()
            return
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount != 0) return // Frames own movement; repeats never add jumps.
            if (isHolding) advance(event.eventTime, durationMs)
            val base = previewPositionMs ?: positionMs
            activeKey = event.keyCode
            downTimeMs = event.downTime
            anchorTimeMs = event.eventTime
            lastSampleMs = anchorTimeMs
            initialStepMs = stepMs
            anchorPositionMs = (base.coerceIn(0L, durationMs) +
                if (activeKey == KeyEvent.KEYCODE_DPAD_RIGHT) stepMs else -stepMs).coerceIn(0L, durationMs)
            previewPositionMs = anchorPositionMs
            isHolding = true
        } else if (event.action == KeyEvent.ACTION_UP && isHolding &&
            activeKey == event.keyCode && downTimeMs == event.downTime
        ) {
            advance(event.eventTime, durationMs)
            val target = previewPositionMs
            // Commit before clearing so the consumer can install its pending target without flicker.
            if (target != null) commit(target)
            cancel()
        }
    }

    fun advance(nowMs: Long, durationMs: Long) {
        if (!isHolding) return
        if (durationMs <= 0L) { cancel(); return }
        lastSampleMs = maxOf(nowMs, lastSampleMs)
        val elapsedMs = (lastSampleMs - anchorTimeMs).coerceAtLeast(0L)
        var distanceMs = 0L
        // Count scheduled jumps in each tier instead of accumulating frame/repeat deltas.
        // Five bounded calculations also catch up exactly after a delayed frame.
        for ((index, step) in HOLD_STEPS_MS.withIndex()) {
            val tierStart = index * 1_000L
            val tierEnd = if (index == HOLD_STEPS_MS.lastIndex) elapsedMs else
                minOf(elapsedMs, (index + 1) * 1_000L - 1L)
            val jumps = (jumpCountThrough(tierEnd) - jumpCountThrough(tierStart - 1L)).coerceAtLeast(0L)
            distanceMs += jumps * maxOf(initialStepMs, step)
        }
        previewPositionMs = if (activeKey == KeyEvent.KEYCODE_DPAD_RIGHT) {
            anchorPositionMs + distanceMs.coerceAtMost((durationMs - anchorPositionMs).coerceAtLeast(0L))
        } else {
            anchorPositionMs - distanceMs.coerceAtMost(anchorPositionMs)
        }.coerceIn(0L, durationMs)
    }

    private fun jumpCountThrough(elapsedMs: Long): Long =
        if (elapsedMs < 400L) 0L else (elapsedMs - 400L) / 250L + 1L

    private companion object {
        val HOLD_STEPS_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}

/** Runs only during a held gesture, and invalidates it before a new playback identity can use it. */
@Composable
internal fun RunRecordedDirectionalSeek(
    state: VideoPlayerState,
    playbackIdentity: Any,
    enabled: Boolean,
    durationMs: Long,
) {
    val gesture = state.directionalSeek
    val duration by rememberUpdatedState(durationMs)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(gesture, playbackIdentity, enabled, owner) {
        gesture.cancel()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) gesture.cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); gesture.cancel() }
    }
    LaunchedEffect(gesture, playbackIdentity, enabled, gesture.isHolding) {
        if (!enabled || !gesture.isHolding) return@LaunchedEffect
        // Frame clock supplies elapsed time; KeyEvent uses uptime. Anchor to the first frame so
        // Compose test clocks and devices share the same deterministic elapsed-time behavior.
        var firstFrame: Long? = null
        val startedAtMs = android.os.SystemClock.uptimeMillis()
        while (gesture.isHolding) {
            withFrameNanos { frame ->
                if (firstFrame == null) firstFrame = frame
                gesture.advance(startedAtMs + (frame - firstFrame) / 1_000_000L, duration)
            }
        }
    }
}
