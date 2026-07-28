package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.TreeMap

private const val UNKNOWN_DURATION_MS = 5_000L
private const val TIMELINE_TICK_MS = 33L
private const val PAUSED_TIMELINE_TICK_MS = 100L
private const val MAX_TIMELINE_CUES = 32

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
    if (!visible || cue == null) return

    val bottomAvoidanceOffsetPx = with(LocalDensity.current) {
        bottomAvoidanceOffset.roundToPx()
    }
    val avoidanceStartY =
        cue.planeHeight.coerceAtLeast(1) * bottomAvoidanceStartFraction.coerceIn(0f, 1f)
    val bitmaps = remember(cue) {
        cue.images.map { image ->
            image to image.bitmap.asImageBitmap()
        }
    }

    Canvas(modifier = modifier) {
        val scaleX = size.width / cue.planeWidth.coerceAtLeast(1).toFloat()
        val scaleY = size.height / cue.planeHeight.coerceAtLeast(1).toFloat()
        bitmaps.forEach { (image, bitmap) ->
            val imageDstOffset = IntOffset(
                x = (image.x * scaleX).toInt(),
                y = (image.y * scaleY).toInt()
            )
            val imageDstSize = IntSize(
                width = (image.width * scaleX).toInt().coerceAtLeast(1),
                height = (image.height * scaleY).toInt().coerceAtLeast(1)
            )

            if (bottomAvoidanceOffsetPx <= 0 || image.regions.isNotEmpty()) {
                if (bottomAvoidanceOffsetPx <= 0) {
                    drawImage(
                        image = bitmap,
                        dstOffset = imageDstOffset,
                        dstSize = imageDstSize
                    )
                    return@forEach
                }

                val stationaryRegions = Path()
                val shiftedRegions = Path()
                var hasStationaryRegions = false
                var hasShiftedRegions = false
                image.regions.forEach { region ->
                    val shouldAvoid = region.y + region.height > avoidanceStartY
                    val verticalOffset = if (shouldAvoid) bottomAvoidanceOffsetPx else 0
                    val regionRect = Rect(
                        left = region.x * scaleX,
                        top = region.y * scaleY - verticalOffset,
                        right = (region.x + region.width) * scaleX,
                        bottom = (region.y + region.height) * scaleY - verticalOffset
                    )
                    if (shouldAvoid) {
                        shiftedRegions.addRect(regionRect)
                        hasShiftedRegions = true
                    } else {
                        stationaryRegions.addRect(regionRect)
                        hasStationaryRegions = true
                    }
                }
                if (hasStationaryRegions) {
                    clipPath(stationaryRegions) {
                        drawImage(
                            image = bitmap,
                            dstOffset = imageDstOffset,
                            dstSize = imageDstSize
                        )
                    }
                }
                if (hasShiftedRegions) {
                    clipPath(shiftedRegions) {
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(
                                x = imageDstOffset.x,
                                y = (imageDstOffset.y - bottomAvoidanceOffsetPx).coerceAtLeast(0)
                            ),
                            dstSize = imageDstSize
                        )
                    }
                }
                return@forEach
            }

            val overlapsBottomAvoidanceArea = image.y + image.height > avoidanceStartY
            val avoidanceOffset = if (overlapsBottomAvoidanceArea) {
                bottomAvoidanceOffsetPx
            } else {
                0
            }
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(
                    x = (image.x * scaleX).toInt(),
                    y = ((image.y * scaleY).toInt() - avoidanceOffset).coerceAtLeast(0)
                ),
                dstSize = IntSize(
                    width = (image.width * scaleX).toInt().coerceAtLeast(1),
                    height = (image.height * scaleY).toInt().coerceAtLeast(1)
                )
            )
        }
    }
}
