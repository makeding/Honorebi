package com.beeregg2001.komorebi.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTabLayoutPolicyTest {
    @Test
    fun cardGeometryDoesNotShrinkToFitAdditionalRows() {
        assertEquals(160.dp, AppsTabLayout.cardWidth)
        assertEquals(136.dp, AppsTabLayout.cardHeight)
        assertEquals(90.dp, AppsTabLayout.bannerHeight)
        assertEquals(14.dp, AppsTabLayout.verticalSpacing)
    }

    @Test
    fun gridDoesNotAddBottomPaddingThatWouldForceScrollWhenContentFits() {
        // 内容が収まっているのに最終行でスクロールしないよう、下方向の
        // 余分なスクロール領域は確保しない方針を固定化する。
        assertEquals(24.dp, AppsTabLayout.viewportBottomPadding)
        assertTrue(AppsTabLayout.verticalSpacing < AppsTabLayout.cardHeight)
    }

    @Test
    fun columnCountUsesFixedCardWidthAndSpacing() {
        assertEquals(1, calculateAppGridColumns(159.dp, 160.dp, 12.dp))
        assertEquals(2, calculateAppGridColumns(332.dp, 160.dp, 12.dp))
        assertEquals(5, calculateAppGridColumns(848.dp, 160.dp, 12.dp))
    }
}
