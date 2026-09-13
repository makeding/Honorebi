package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal data class CaptionImageDraw(
    val bitmap: ImageBitmap,
    val offset: IntOffset,
    val size: IntSize,
    val clip: Path? = null,
)

/** One cue and one viewport only. No bitmap copies or changes to bitmap ownership. */
internal class NativeCaptionDrawCache(private val cue: NativeCaptionCue?) {
    private val bitmaps = cue?.images.orEmpty().map { it.bitmap.asImageBitmap() }
    private val regions = cue?.images.orEmpty().map(::captionAvoidanceRegions)
    private var viewport = Size.Unspecified
    private var obstacles = emptyList<Rect>()
    private var targetOffsets = emptyList<List<Float>>()
    private var progress = Float.NaN
    private var draws = emptyList<CaptionImageDraw>()

    fun get(size: Size, avoidanceObstacles: List<Rect>, avoidanceProgress: Float): List<CaptionImageDraw> {
        val targetChanged = size != viewport || obstacles != avoidanceObstacles
        val nextProgress = avoidanceProgress.coerceIn(0f, 1f)
        if (!targetChanged && nextProgress == progress) return draws
        if (targetChanged) {
            viewport = size
            obstacles = avoidanceObstacles.toList()
            targetOffsets = if (cue == null || size.width <= 0 || size.height <= 0) emptyList() else {
                val scaleX = size.width / cue.planeWidth.coerceAtLeast(1)
                val scaleY = size.height / cue.planeHeight.coerceAtLeast(1)
                calculateCaptionObstacleOffsets(regions, obstacles.map {
                    Rect(it.left / scaleX, it.top / scaleY, it.right / scaleX, it.bottom / scaleY)
                })
            }
        }
        progress = nextProgress
        draws = buildDraws(size, nextProgress)
        return draws
    }

    private fun buildDraws(size: Size, progress: Float): List<CaptionImageDraw> {
        if (cue == null || size.width <= 0 || size.height <= 0) return emptyList()
        val scaleX = size.width / cue.planeWidth.coerceAtLeast(1)
        val scaleY = size.height / cue.planeHeight.coerceAtLeast(1)
        return buildList {
            cue.images.forEachIndexed { imageIndex, image ->
                val destination = IntOffset((image.x * scaleX).toInt(), (image.y * scaleY).toInt())
                val destinationSize = IntSize(
                    (image.width * scaleX).toInt().coerceAtLeast(1),
                    (image.height * scaleY).toInt().coerceAtLeast(1)
                )
                val offsets = targetOffsets[imageIndex].map { (it * scaleY * progress).toInt() }
                if (offsets.all { it == 0 }) {
                    add(CaptionImageDraw(bitmaps[imageIndex], destination, destinationSize))
                } else {
                    // Independent groups within a bitmap may have different shifts.
                    // Every region is drawn, with body, background and ruby unchanged.
                    offsets.distinct().forEach { dy ->
                        val clip = Path()
                        regions[imageIndex].forEachIndexed { regionIndex, region ->
                            if (offsets[regionIndex] == dy) clip.addRect(Rect(
                                region.x * scaleX, region.y * scaleY - dy,
                                (region.x + region.width) * scaleX, (region.y + region.height) * scaleY - dy
                            ))
                        }
                        add(CaptionImageDraw(bitmaps[imageIndex],
                            IntOffset(destination.x, destination.y - dy), destinationSize, clip))
                    }
                }
            }
        }
    }
}
