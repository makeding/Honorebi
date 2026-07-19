package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.TreeMap

private const val UNKNOWN_DURATION_MS = 5_000L
private const val TIMELINE_TICK_MS = 33L
private const val PAUSED_TIMELINE_TICK_MS = 100L
private const val MAX_TIMELINE_CUES = 500

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
            if (cue.resetTimeline) {
                timeline.clear()
                cueState.value = null
            } else {
                timeline[cue.ptsMs] = cue
                while (timeline.size > MAX_TIMELINE_CUES) {
                    timeline.pollFirstEntry()
                }
            }
        }
    }
    LaunchedEffect(enabled, resetKey) {
        while (enabled) {
            val positionMs = currentPositionMsProvider.value().coerceAtLeast(0L)
            val cue = timeline.floorEntry(positionMs)?.value
            cueState.value = when {
                cue == null || cue.clearScreen || cue.images.isEmpty() -> null
                cue.durationMs == -1L -> cue
                cue.durationMs > 0L && positionMs < cue.ptsMs + cue.durationMs -> cue
                cue.durationMs <= 0L && positionMs < cue.ptsMs + UNKNOWN_DURATION_MS -> cue
                else -> null
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
    modifier: Modifier = Modifier
) {
    if (!visible || cue == null) return

    val bitmaps = remember(cue) {
        cue.images.mapNotNull { image ->
            image.toBitmap()?.let { image to it.asImageBitmap() }
        }
    }

    Canvas(modifier = modifier) {
        val scaleX = size.width / cue.planeWidth.coerceAtLeast(1).toFloat()
        val scaleY = size.height / cue.planeHeight.coerceAtLeast(1).toFloat()
        bitmaps.forEach { (image, bitmap) ->
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(
                    x = (image.x * scaleX).toInt(),
                    y = (image.y * scaleY).toInt()
                ),
                dstSize = IntSize(
                    width = (image.width * scaleX).toInt().coerceAtLeast(1),
                    height = (image.height * scaleY).toInt().coerceAtLeast(1)
                )
            )
        }
    }
}

private fun NativeCaptionImage.toBitmap(): Bitmap? {
    if (width <= 0 || height <= 0 || rgba.isEmpty()) return null
    val pixels = IntArray(width * height)
    var out = 0
    for (row in 0 until height) {
        var offset = row * stride
        for (col in 0 until width) {
            if (offset + 3 >= rgba.size) return null
            val r = rgba[offset].toInt() and 0xff
            val g = rgba[offset + 1].toInt() and 0xff
            val b = rgba[offset + 2].toInt() and 0xff
            val a = rgba[offset + 3].toInt() and 0xff
            pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            offset += 4
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
