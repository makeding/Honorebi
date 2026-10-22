package com.beeregg2001.komorebi.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min

private val MiniPlayerWidth = 320.dp
private val MiniPlayerHeight = 180.dp
private val MiniPlayerEndPadding = 32.dp
private val MiniPlayerCornerRadius = 12.dp

/**
 * Animates the root player between its full-screen and mini-player bounds without sending
 * per-frame constraints through the video, caption and controller subtree.
 *
 * The child stays measured at the endpoint where the transition began. Only its layer placement
 * changes while [endpointFraction] is animating. Once an endpoint is reached, the child is
 * measured once at that endpoint's real constraints; the two layer transforms are equivalent at
 * the hand-off, so the visible bounds do not jump.
 */
@Composable
fun MiniPlayerTransformLayout(
    isMiniPlayerMode: Boolean,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(durationMillis = 400),
    content: @Composable BoxScope.() -> Unit,
) {
    val endpointFraction = remember {
        Animatable(if (isMiniPlayerMode) 1f else 0f)
    }
    var measuredAtMiniEndpoint by remember { mutableStateOf(isMiniPlayerMode) }
    val currentMiniMode by rememberUpdatedState(isMiniPlayerMode)

    LaunchedEffect(isMiniPlayerMode, animationSpec) {
        val targetFraction = if (isMiniPlayerMode) 1f else 0f
        endpointFraction.animateTo(targetFraction, animationSpec)
        if (currentMiniMode == isMiniPlayerMode && endpointFraction.value == targetFraction) {
            measuredAtMiniEndpoint = isMiniPlayerMode
        }
    }

    Layout(
        modifier = modifier,
        content = { Box(Modifier.fillMaxSize(), content = content) },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val endPaddingPx = MiniPlayerEndPadding.roundToPx()
        val miniWidth = min(MiniPlayerWidth.roundToPx(), (width - endPaddingPx).coerceAtLeast(0))
        val miniHeight = min(MiniPlayerHeight.roundToPx(), (height - endPaddingPx).coerceAtLeast(0))
        val childWidth = if (measuredAtMiniEndpoint) miniWidth else width
        val childHeight = if (measuredAtMiniEndpoint) miniHeight else height
        val placeable = measurables.single().measure(Constraints.fixed(childWidth, childHeight))
        val miniLeft = (width - endPaddingPx - miniWidth).coerceAtLeast(0)
        val miniTop = (height - endPaddingPx - miniHeight).coerceAtLeast(0)
        val baseLeft = if (measuredAtMiniEndpoint) miniLeft else 0
        val baseTop = if (measuredAtMiniEndpoint) miniTop else 0

        layout(width, height) {
            placeable.placeWithLayer(baseLeft, baseTop) {
                val fraction = endpointFraction.value.coerceIn(0f, 1f)
                val fullToMiniScaleX = if (width > 0) miniWidth.toFloat() / width else 1f
                val fullToMiniScaleY = if (height > 0) miniHeight.toFloat() / height else 1f
                if (measuredAtMiniEndpoint) {
                    val miniToFullScaleX = if (miniWidth > 0) width.toFloat() / miniWidth else 1f
                    val miniToFullScaleY = if (miniHeight > 0) height.toFloat() / miniHeight else 1f
                    scaleX = miniToFullScaleX + (1f - miniToFullScaleX) * fraction
                    scaleY = miniToFullScaleY + (1f - miniToFullScaleY) * fraction
                    translationX = -miniLeft * (1f - fraction)
                    translationY = -miniTop * (1f - fraction)
                } else {
                    scaleX = 1f + (fullToMiniScaleX - 1f) * fraction
                    scaleY = 1f + (fullToMiniScaleY - 1f) * fraction
                    translationX = miniLeft * fraction
                    translationY = miniTop * fraction
                }
                transformOrigin = TransformOrigin(0f, 0f)
                clip = isMiniPlayerMode
                shape = if (isMiniPlayerMode) {
                    val visibleScale = min(scaleX, scaleY).coerceAtLeast(0.001f)
                    RoundedCornerShape(MiniPlayerCornerRadius / visibleScale)
                } else {
                    RectangleShape
                }
            }
        }
    }
}
