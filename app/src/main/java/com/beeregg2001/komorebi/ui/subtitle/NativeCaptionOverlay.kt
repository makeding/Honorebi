package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap

private const val UNKNOWN_DURATION_MS = 5_000L
private const val MAX_LIVE_CAPTION_RECHECK_MS = 1_000L
private const val MAX_TIMELINE_CUES = 12

internal fun nextLiveCaptionUpdateDelayMs(
    timeline: TreeMap<Long, NativeCaptionCue>,
    positionMs: Long,
    clockRunning: Boolean,
): Long? {
    if (!clockRunning) return null
    val currentEntry = timeline.floorEntry(positionMs)
    val currentCue = currentEntry?.value
    val expiryMs = currentCue
        ?.takeUnless { it.clearScreen || it.images.isEmpty() || it.durationMs == -1L }
        ?.let { cue -> cue.ptsMs + if (cue.durationMs > 0L) cue.durationMs else UNKNOWN_DURATION_MS }
        ?.takeIf { it > positionMs }
    val nextCueMs = timeline.higherKey(positionMs)
    val nextBoundaryMs = when {
        expiryMs != null && nextCueMs != null -> minOf(expiryMs, nextCueMs)
        expiryMs != null -> expiryMs
        else -> nextCueMs
    } ?: return null
    return (nextBoundaryMs - positionMs).coerceIn(1L, MAX_LIVE_CAPTION_RECHECK_MS)
}

internal fun captionAvoidanceRegions(image: NativeCaptionImage): List<NativeCaptionRegion> =
    image.regions.ifEmpty {
        listOf(NativeCaptionRegion(image.x, image.y, image.width, image.height))
    }

@Composable
fun rememberNativeCaptionCue(
    events: Flow<NativeCaptionCue>,
    enabled: Boolean,
    resetKey: Any? = null,
    clockRunning: Boolean = true,
    positionMsProvider: () -> Long
): MutableState<NativeCaptionCue?> {
    val cueState = remember { mutableStateOf<NativeCaptionCue?>(null) }
    val timeline = remember { TreeMap<Long, NativeCaptionCue>() }
    val invalidations = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentClockRunning = rememberUpdatedState(clockRunning)
    val currentPositionMsProvider = rememberUpdatedState(positionMsProvider)
    LaunchedEffect(resetKey) {
        timeline.clear()
        cueState.value = null
        invalidations.trySend(Unit)
    }
    LaunchedEffect(clockRunning, resetKey) {
        invalidations.trySend(Unit)
    }
    LaunchedEffect(events, enabled, resetKey) {
        if (!enabled) {
            timeline.clear()
            cueState.value = null
            invalidations.trySend(Unit)
            return@LaunchedEffect
        }
        events.collect { cue ->
            when (cue.timelineCommand) {
                NativeCaptionCue.TIMELINE_COMMAND_RESET -> {
                    timeline.clear()
                    cueState.value = null
                    invalidations.trySend(Unit)
                    return@collect
                }
                NativeCaptionCue.TIMELINE_COMMAND_REPLACE_FROM -> {
                    timeline.tailMap(cue.ptsMs, true).clear()
                    invalidations.trySend(Unit)
                    return@collect
                }
            }
            timeline[cue.ptsMs] = cue
            while (timeline.size > MAX_TIMELINE_CUES) {
                timeline.pollFirstEntry()
            }
            invalidations.trySend(Unit)
        }
    }
    LaunchedEffect(enabled, resetKey) {
        if (!enabled) {
            cueState.value = null
            return@LaunchedEffect
        }
        while (isActive) {
            val positionMs = currentPositionMsProvider.value().coerceAtLeast(0L)
            val currentEntry = timeline.floorEntry(positionMs)
            val cue = currentEntry?.value
            cueState.value = when {
                cue == null || cue.clearScreen || cue.images.isEmpty() -> null
                cue.durationMs == -1L -> cue
                cue.durationMs > 0L && positionMs < cue.ptsMs + cue.durationMs -> cue
                cue.durationMs <= 0L && positionMs < cue.ptsMs + UNKNOWN_DURATION_MS -> cue
                else -> null
            }
            if (currentEntry != null) {
                timeline.headMap(currentEntry.key, false).clear()
            }
            val delayMs = nextLiveCaptionUpdateDelayMs(timeline, positionMs, currentClockRunning.value)
            if (delayMs == null) {
                invalidations.receive()
            } else {
                withTimeoutOrNull(delayMs) { invalidations.receive() }
            }
        }
    }
    return cueState
}

@Composable
fun NativeCaptionOverlay(
    cue: NativeCaptionCue?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    avoidanceObstacles: List<Rect> = emptyList(),
    avoidanceProgress: Float = 0f,
    avoidanceProgressProvider: (() -> Float)? = null,
) {
    // Keep the overlay bounds allocated through empty, disabled and transition states.

    var origin by remember { mutableStateOf(Offset.Zero) }
    val localObstacles = remember(avoidanceObstacles, origin) {
        avoidanceObstacles.map { it.translate(-origin) }
    }
    val drawCache = remember(cue) { NativeCaptionDrawCache(cue) }
    Canvas(modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        if (!visible || cue == null) return@Canvas
        val resolvedProgress = avoidanceProgressProvider?.invoke() ?: avoidanceProgress
        drawCache.get(size, localObstacles, resolvedProgress).forEach { draw ->
            if (draw.clip == null) {
                drawImage(draw.bitmap, dstOffset = draw.offset, dstSize = draw.size)
            } else {
                clipPath(draw.clip) {
                    drawImage(draw.bitmap, dstOffset = draw.offset, dstSize = draw.size)
                }
            }
        }
    }
}
