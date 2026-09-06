package com.beeregg2001.komorebi.ui.subtitle

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

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
}

@Composable
fun rememberRecordedCaptionState(identity: Any?): RecordedCaptionState {
    val state = remember(identity) { RecordedCaptionState() }
    DisposableEffect(state) { onDispose { state.timeline.switchQuality(); state.clear() } }
    return state
}

@Composable
fun UpdateRecordedCaptionState(state: RecordedCaptionState, enabled: Boolean, language: Int, positionMs: () -> Long) {
    val position = rememberUpdatedState(positionMs)
    LaunchedEffect(state, enabled, language) {
        state.clear(NativeCaptionCue.TYPE_CAPTION)
    }
    LaunchedEffect(state, enabled) {
        while (true) {
            if (!enabled) state.clear(NativeCaptionCue.TYPE_CAPTION)
            val currentPosition = position.value().coerceAtLeast(0L)
            state.caption.value = state.timeline.current(NativeCaptionCue.TYPE_CAPTION, currentPosition)
            state.superimpose.value = state.timeline.current(NativeCaptionCue.TYPE_SUPERIMPOSE, currentPosition)
            delay(33L)
        }
    }
}
