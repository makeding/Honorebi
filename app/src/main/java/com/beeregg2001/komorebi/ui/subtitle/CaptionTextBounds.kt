package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult

/** Reports visible line content, excluding the Text's allocated width and padding. */
internal class CaptionTextBounds(private val report: (List<Rect>) -> Unit) {
    private var coordinates: LayoutCoordinates? = null
    private var layout: TextLayoutResult? = null

    fun onPositioned(value: LayoutCoordinates) { coordinates = value; update() }
    fun onTextLayout(value: TextLayoutResult) { layout = value; update() }

    private fun update() {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        val layout = layout ?: return
        val origin = coordinates.positionInRoot()
        val viewport = Rect(0f, 0f, coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        report((0 until layout.lineCount).mapNotNull { line ->
            val bounds = Rect(layout.getLineLeft(line), layout.getLineTop(line),
                layout.getLineRight(line), layout.getLineBottom(line)).intersect(viewport)
            bounds.takeIf { it.width > 0f && it.height > 0f }?.translate(origin)
        })
    }
}

@Composable
internal fun rememberCaptionTextBounds(report: (List<Rect>) -> Unit): CaptionTextBounds {
    val currentReport = rememberUpdatedState(report)
    return remember { CaptionTextBounds { currentReport.value(it) } }
}
