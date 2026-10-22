package com.beeregg2001.komorebi.ui.subtitle

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

class RecordedCaptionState {
    var resetSerial by mutableIntStateOf(0)
        private set
    fun seek() { resetSerial++; clear() }
    val timeline = RecordedCaptionTimeline(SystemClock::elapsedRealtime)
    val caption = mutableStateOf<NativeCaptionCue?>(null)
    val superimpose = mutableStateOf<NativeCaptionCue?>(null)
    fun switchQuality() { timeline.switchQuality(caption.value, superimpose.value) }
    fun clear(type: Int? = null) {
        timeline.clear(type)
        if (type == null || type == NativeCaptionCue.TYPE_CAPTION) caption.value = null
        if (type == null || type == NativeCaptionCue.TYPE_SUPERIMPOSE) superimpose.value = null
    }

    internal fun update(enabled: Boolean, positionMs: Long) {
        if (enabled) {
            caption.value = timeline.current(NativeCaptionCue.TYPE_CAPTION, positionMs)
        } else {
            // The decoder can deliver an already-buffered caption after the
            // preference changes. Discard that layer on every external wake,
            // while leaving superimpose independent and visible.
            clear(NativeCaptionCue.TYPE_CAPTION)
        }
        superimpose.value = timeline.current(NativeCaptionCue.TYPE_SUPERIMPOSE, positionMs)
    }
}

internal class RecordedCaptionPlayerListener(
    private val onTimelineChanged: () -> Unit,
) : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) = onTimelineChanged()

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = onTimelineChanged()

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = onTimelineChanged()
}

@Composable
fun rememberRecordedCaptionState(identity: Any?): RecordedCaptionState {
    val state = remember(identity) { RecordedCaptionState() }
    DisposableEffect(state) { onDispose { state.timeline.switchQuality(); state.clear() } }
    return state
}

@Composable
fun UpdateRecordedCaptionState(
    state: RecordedCaptionState,
    enabled: Boolean,
    language: Int,
    player: Player,
    positionMs: () -> Long,
) {
    val position = rememberUpdatedState(positionMs)
    LaunchedEffect(state, enabled, language) {
        state.clear(NativeCaptionCue.TYPE_CAPTION)
    }
    DisposableEffect(state, player) {
        val listener = RecordedCaptionPlayerListener(state.timeline::invalidate)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(state, enabled, player) {
        while (true) {
            val currentPosition = position.value().coerceAtLeast(0L)
            state.update(enabled, currentPosition)
            val nextUpdateDelayMs = state.timeline.nextUpdateDelayMs(
                positionMs = currentPosition,
                clockRunning = player.isPlaying,
                playbackSpeed = player.playbackParameters.speed,
            )
            state.timeline.awaitInvalidation(nextUpdateDelayMs)
        }
    }
}
