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
    private var offsetPx = Int.MIN_VALUE
    private var startFraction = Float.NaN
    private var draws = emptyList<CaptionImageDraw>()

    fun get(size: Size, avoidanceOffsetPx: Int, avoidanceStartFraction: Float): List<CaptionImageDraw> {
        if (size == viewport && avoidanceOffsetPx == offsetPx && avoidanceStartFraction == startFraction) {
            return draws
        }
        viewport = size
        offsetPx = avoidanceOffsetPx
        startFraction = avoidanceStartFraction
        draws = buildDraws(size, avoidanceOffsetPx, avoidanceStartFraction)
        return draws
    }

    private fun buildDraws(size: Size, offset: Int, start: Float): List<CaptionImageDraw> {
        if (cue == null || size.width <= 0 || size.height <= 0) return emptyList()
        val scaleX = size.width / cue.planeWidth.coerceAtLeast(1)
        val scaleY = size.height / cue.planeHeight.coerceAtLeast(1)
        // All images participate before any clipping, preserving body/ruby propagation.
        val masks = if (offset > 0) calculateCueBottomAvoidanceMasks(
            regions, cue.planeHeight.coerceAtLeast(1) * start.coerceIn(0f, 1f), offset / scaleY
        ) else emptyList()
        return buildList {
            cue.images.forEachIndexed { imageIndex, image ->
                val destination = IntOffset((image.x * scaleX).toInt(), (image.y * scaleY).toInt())
                val destinationSize = IntSize(
                    (image.width * scaleX).toInt().coerceAtLeast(1),
                    (image.height * scaleY).toInt().coerceAtLeast(1)
                )
                if (offset <= 0) {
                    add(CaptionImageDraw(bitmaps[imageIndex], destination, destinationSize))
                } else {
                    val stationary = Path()
                    val shifted = Path()
                    var hasStationary = false
                    var hasShifted = false
                    regions[imageIndex].forEachIndexed { regionIndex, region ->
                        val moving = masks[imageIndex][regionIndex]
                        val dy = if (moving) offset else 0
                        val bounds = Rect(
                            region.x * scaleX, region.y * scaleY - dy,
                            (region.x + region.width) * scaleX, (region.y + region.height) * scaleY - dy
                        )
                        if (moving) { shifted.addRect(bounds); hasShifted = true }
                        else { stationary.addRect(bounds); hasStationary = true }
                    }
                    if (hasStationary) add(CaptionImageDraw(bitmaps[imageIndex], destination, destinationSize, stationary))
                    if (hasShifted) add(CaptionImageDraw(
                        bitmaps[imageIndex], IntOffset(destination.x, destination.y - offset), destinationSize, shifted
                    ))
                }
            }
        }
    }
}
