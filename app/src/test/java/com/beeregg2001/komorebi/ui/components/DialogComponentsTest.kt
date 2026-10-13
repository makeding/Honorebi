package com.beeregg2001.komorebi.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogComponentsTest {
    @Test
    fun dialogForegroundUsesDarkTextForBrightActionBackgrounds() {
        assertEquals(Color.Black, dialogForeground(Color(0xFFFFE066)))
    }

    @Test
    fun dialogForegroundUsesLightTextForDarkDestructiveBackgrounds() {
        assertEquals(Color.White, dialogForeground(Color(0xFFD32F2F)))
    }

    @Test fun actionForegroundHasAtLeastAaContrastForAccentFamilies() {
        listOf(Color(0xFFAEE8F2), Color(0xFFFFAB66), Color(0xFFD32F2F)).forEach { background ->
            assertTrue("$background", contrastRatio(dialogForeground(background), background) >= 4.5f)
        }
    }
}
