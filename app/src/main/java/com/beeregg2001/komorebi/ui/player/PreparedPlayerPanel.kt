package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.awaitCancellation

/** Prepares an unplaced panel after the foreground UI has had a frame to draw. */
@Composable
internal fun PreparedPlayerPanel(
    visible: Boolean,
    prepare: Boolean,
    contentKey: Any? = Unit,
    modifier: Modifier = Modifier,
    content: @Composable (isActive: Boolean) -> Unit,
) {
    val layoutState = remember(contentKey) { SubcomposeLayoutState() }
    val latestContent by rememberUpdatedState(content)
    val latestVisible by rememberUpdatedState(visible)
    val panelContent: @Composable () -> Unit = remember { { latestContent(latestVisible) } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var foregroundDrawn by remember(prepare, contentKey) { mutableStateOf(false) }
    val slot = "player-panel"

    SubcomposeLayout(
        state = layoutState,
        modifier = modifier.onSizeChanged { size = it }.drawWithContent {
            drawContent()
            if (prepare && !visible && !foregroundDrawn) foregroundDrawn = true
        },
    ) { constraints ->
        val placeables = if (visible) {
            subcompose(slot, panelContent).map { it.measure(constraints) }
        } else {
            emptyList()
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }

    LaunchedEffect(layoutState, prepare, visible, size, foregroundDrawn) {
        if (!prepare || visible || !foregroundDrawn || size == IntSize.Zero) return@LaunchedEffect
        // Preparation follows a real draw, then yields to the next frame.
        withFrameNanos { }
        val handle = layoutState.precompose(slot, panelContent)
        try {
            repeat(handle.placeablesCount) { index ->
                handle.premeasure(index, Constraints.fixed(size.width, size.height))
            }
            awaitCancellation()
        } finally {
            // Opening cancels this effect before the next measure pass consumes
            // the slot. Keep it for that handoff; other cancellations discard it.
            if (!latestVisible) handle.dispose()
        }
    }
}
