package com.beeregg2001.komorebi.ui.epg

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import com.beeregg2001.komorebi.ui.epg.engine.EpgAnimValues

/** Read the returned values only from drawing, so animation frames do not recompose the grid. */
@Composable
internal fun epgDrawAnimation(
    scrollX: Float, scrollY: Float, focusX: Float, focusY: Float, focusHeight: Float,
    isJumping: Boolean
): () -> EpgAnimValues {
    val spec = if (isJumping) snap() else spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2500f
    )
    val sx = animateFloatAsState(scrollX, spec, label = "sX")
    val sy = animateFloatAsState(scrollY, spec, label = "sY")
    val fx = animateFloatAsState(focusX, spec, label = "aX")
    val fy = animateFloatAsState(focusY, spec, label = "aY")
    val fh = animateFloatAsState(focusHeight, spec, label = "aH")
    return { EpgAnimValues(sx.value, sy.value, fx.value, fy.value, fh.value) }
}
