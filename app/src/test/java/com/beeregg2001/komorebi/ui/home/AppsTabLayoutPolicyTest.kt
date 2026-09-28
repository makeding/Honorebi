package com.beeregg2001.komorebi.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTabLayoutPolicyTest {
    @Test
    fun cardGeometryDoesNotShrinkToFitAdditionalRows() {
        assertEquals(160.dp, AppsTabLayout.cardWidth)
        assertEquals(136.dp, AppsTabLayout.idealCardHeight)
        assertTrue(AppsTabLayout.minCardHeight < AppsTabLayout.idealCardHeight)
        assertEquals(12.dp, AppsTabLayout.verticalSpacing)
    }

    @Test
    fun threeRowsFitOnA1080pAndroidTvViewport() {
        // 1080p の Android TV は概ね 540dp 高。外殻の上部バー(80dp)と
        // 上下パディングを引いた表示領域で 3 行が収まることを保証する。
        // 収まらないと最終行が半端に切れ、フォーカス移動で微スクロールする。
        val viewport =
            540.dp - 80.dp - AppsTabLayout.topPadding - AppsTabLayout.viewportBottomPadding
        val neededRows = 3
        val maxCardHeightToFit =
            (viewport - AppsTabLayout.verticalSpacing * (neededRows - 1) - AppsTabLayout.gridBottomGap) / neededRows
        val effectiveCardHeight = AppsTabLayout.idealCardHeight
            .coerceAtMost(maxCardHeightToFit)
            .coerceAtLeast(AppsTabLayout.minCardHeight)
        val contentHeight =
            effectiveCardHeight * neededRows + AppsTabLayout.verticalSpacing * (neededRows - 1)
        assertTrue(
            "3 rows must fit: content=$contentHeight viewport=$viewport",
            contentHeight <= viewport,
        )
        assertTrue(effectiveCardHeight >= AppsTabLayout.minCardHeight)
    }

    @Test
    fun columnCountUsesFixedCardWidthAndSpacing() {
        assertEquals(1, calculateAppGridColumns(159.dp, 160.dp, 12.dp))
        assertEquals(2, calculateAppGridColumns(332.dp, 160.dp, 12.dp))
        assertEquals(5, calculateAppGridColumns(848.dp, 160.dp, 12.dp))
    }
}
