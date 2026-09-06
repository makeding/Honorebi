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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.TreeMap

private const val UNKNOWN_DURATION_MS = 5_000L
private const val TIMELINE_TICK_MS = 33L
private const val PAUSED_TIMELINE_TICK_MS = 100L
private const val MAX_TIMELINE_CUES = 12

private fun regionsOverlap(
    first: NativeCaptionRegion,
    second: NativeCaptionRegion,
    firstTop: Float = first.y.toFloat(),
    firstBottom: Float = (first.y + first.height).toFloat()
): Boolean =
    first.x < second.x + second.width &&
        first.x + first.width > second.x &&
        firstTop <= second.y + second.height &&
        firstBottom >= second.y

internal fun calculateBottomAvoidanceMask(
    regions: List<NativeCaptionRegion>,
    avoidanceStartY: Float,
    avoidanceOffset: Float
): BooleanArray {
    val shouldAvoid = BooleanArray(regions.size) { index ->
        val region = regions[index]
        region.y + region.height > avoidanceStartY
    }
    if (avoidanceOffset <= 0f) return shouldAvoid

    var changed: Boolean
    do {
        changed = false
        regions.indices.filter { shouldAvoid[it] }.forEach { movingIndex ->
            val moving = regions[movingIndex]
            val sweptTop = moving.y - avoidanceOffset
            val sweptBottom = (moving.y + moving.height).toFloat()
            regions.indices.filter { !shouldAvoid[it] }.forEach { stationaryIndex ->
                if (regionsOverlap(
                        first = moving,
                        second = regions[stationaryIndex],
                        firstTop = sweptTop,
                        firstBottom = sweptBottom
                    )
                ) {
                    shouldAvoid[stationaryIndex] = true
                    changed = true
                }
            }
        }
    } while (changed)
    return shouldAvoid
}

internal fun captionAvoidanceRegions(image: NativeCaptionImage): List<NativeCaptionRegion> =
    image.regions.ifEmpty {
        listOf(NativeCaptionRegion(image.x, image.y, image.width, image.height))
    }

internal fun calculateCueBottomAvoidanceMasks(
    imageRegions: List<List<NativeCaptionRegion>>,
    avoidanceStartY: Float,
    avoidanceOffset: Float
): List<BooleanArray> {
    if (avoidanceOffset <= 0f) return imageRegions.map { BooleanArray(it.size) }
    val mask = calculateBottomAvoidanceMask(imageRegions.flatten(), avoidanceStartY, avoidanceOffset)
    var start = 0
    return imageRegions.map { regions ->
        mask.copyOfRange(start, start + regions.size).also { start += regions.size }
    }
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
    val currentClockRunning = rememberUpdatedState(clockRunning)
    val currentPositionMsProvider = rememberUpdatedState(positionMsProvider)
    LaunchedEffect(resetKey) {
        timeline.clear()
        cueState.value = null
    }
    LaunchedEffect(events, enabled, resetKey) {
        if (!enabled) {
            timeline.clear()
            cueState.value = null
            return@LaunchedEffect
        }
        events.collect { cue ->
            when (cue.timelineCommand) {
                NativeCaptionCue.TIMELINE_COMMAND_RESET -> {
                    timeline.clear()
                    cueState.value = null
                    return@collect
                }
                NativeCaptionCue.TIMELINE_COMMAND_REPLACE_FROM -> {
                    timeline.tailMap(cue.ptsMs, true).clear()
                    return@collect
                }
            }
            timeline[cue.ptsMs] = cue
            while (timeline.size > MAX_TIMELINE_CUES) {
                timeline.pollFirstEntry()
            }
        }
    }
    LaunchedEffect(enabled, resetKey) {
        while (enabled) {
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
            delay(if (currentClockRunning.value) TIMELINE_TICK_MS else PAUSED_TIMELINE_TICK_MS)
        }
    }
    return cueState
}

@Composable
fun NativeCaptionOverlay(
    cue: NativeCaptionCue?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    bottomAvoidanceOffset: Dp = 0.dp,
    bottomAvoidanceStartFraction: Float = 1f
) {
    // Keep the overlay bounds allocated through empty, disabled and transition states.

    val bottomAvoidanceOffsetPx = with(LocalDensity.current) {
        bottomAvoidanceOffset.roundToPx()
    }
    val drawCache = remember(cue) { NativeCaptionDrawCache(cue) }
    Canvas(modifier = modifier) {
        if (!visible || cue == null) return@Canvas
        drawCache.get(size, bottomAvoidanceOffsetPx, bottomAvoidanceStartFraction).forEach { draw ->
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
