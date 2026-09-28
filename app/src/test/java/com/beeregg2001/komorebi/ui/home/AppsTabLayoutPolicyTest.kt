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
    fun bottomScrollSafeAreaCanExposeAnEntireFinalRow() {
        assertTrue(AppsTabLayout.bottomScrollSafeArea >= AppsTabLayout.cardHeight)
        assertEquals(24.dp, AppsTabLayout.viewportBottomPadding)
    }

    @Test
    fun columnCountUsesFixedCardWidthAndSpacing() {
        assertEquals(1, calculateAppGridColumns(159.dp, 160.dp, 12.dp))
        assertEquals(2, calculateAppGridColumns(332.dp, 160.dp, 12.dp))
        assertEquals(5, calculateAppGridColumns(848.dp, 160.dp, 12.dp))
    }

    @Test
    fun gridKeepsRoomBelowTheLastRowForTheFocusedCard() {
        assertTrue(AppsTabLayout.gridBottomPadding >= AppsTabLayout.verticalSpacing)
    }

    @Test
    fun catalogEntryIdentifiersCannotCollideWithEachOther() {
        assertTrue(HIDDEN_CATALOG_STABLE_ID != VISIBLE_CATALOG_STABLE_ID)
        assertTrue(HIDDEN_CATALOG_STABLE_ID.isNotBlank())
        assertTrue(VISIBLE_CATALOG_STABLE_ID.isNotBlank())
    }
}
