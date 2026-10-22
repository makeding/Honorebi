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

private suspend fun awaitPlayerPanelFrame(operation: () -> Unit) {
    withFrameNanos { operation() }
}

/** Prepares an unplaced panel in later frames after the foreground UI has drawn. */
@Composable
internal fun PreparedPlayerPanel(
    visible: Boolean,
    prepare: Boolean,
    contentKey: Any? = Unit,
    modifier: Modifier = Modifier,
    awaitPreparationFrame: suspend (() -> Unit) -> Unit = ::awaitPlayerPanelFrame,
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
        // Preparation follows a real draw, then starts on a later frame.
        awaitPreparationFrame { }
        val handle = layoutState.precompose(slot, panelContent)
        try {
            // Keep composition and measurement off the same frame. If a panel
            // exposes multiple root placeables, measure at most one per frame.
            repeat(handle.placeablesCount) { index ->
                awaitPreparationFrame {
                    handle.premeasure(index, Constraints.fixed(size.width, size.height))
                }
            }
            awaitCancellation()
        } finally {
            // Opening cancels this effect before the next measure pass consumes
            // the slot. Keep it for that handoff; other cancellations discard it.
            if (!latestVisible) handle.dispose()
        }
    }
}
