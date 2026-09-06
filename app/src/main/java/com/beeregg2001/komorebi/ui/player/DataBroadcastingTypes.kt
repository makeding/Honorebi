package com.beeregg2001.komorebi.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

data class DataBroadcastingRemoteCommand(
    val id: Long,
    val key: String,
)

data class B60MediaPlane(
    val visible: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val screenWidth: Float,
    val screenHeight: Float,
)

val B60_INITIAL_MEDIA_PLANE = B60MediaPlane(
    visible = true,
    x = 864f,
    y = 56f,
    width = 2880f,
    height = 1620f,
    screenWidth = 3840f,
    screenHeight = 2160f,
)

/** Applies BML's absolute media-plane coordinates inside the stable player surface. */
fun Modifier.b60MediaPlane(plane: B60MediaPlane): Modifier = layout { measurable, constraints ->
    val screenWidth = plane.screenWidth.takeIf { it > 0f } ?: 3840f
    val screenHeight = plane.screenHeight.takeIf { it > 0f } ?: 2160f
    val width = (constraints.maxWidth * plane.width / screenWidth)
        .roundToInt()
        .coerceIn(1, constraints.maxWidth)
    val height = (constraints.maxHeight * plane.height / screenHeight)
        .roundToInt()
        .coerceIn(1, constraints.maxHeight)
    val x = (constraints.maxWidth * plane.x / screenWidth)
        .roundToInt()
        .coerceIn(0, (constraints.maxWidth - width).coerceAtLeast(0))
    val y = (constraints.maxHeight * plane.y / screenHeight)
        .roundToInt()
        .coerceIn(0, (constraints.maxHeight - height).coerceAtLeast(0))
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(x, y)
    }
}
