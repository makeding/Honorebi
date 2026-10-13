package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.ui.components.contrastRatio
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class SettingsGuideContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualGuideLabelsStayReadableWhenFocusedAcrossThemes() {
        var theme by mutableStateOf(AppTheme.MONOTONE)
        var background = Color.Unspecified
        compose.setContent {
            KomorebiTheme(theme) {
                background = MaterialTheme.colorScheme.primary
                SettingsGuideScreen(onSelectCategory = {}, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText("一般").assertIsFocused()
        for (value in listOf(AppTheme.MONOTONE, AppTheme.HIGHTONE, AppTheme.AUTUMN)) {
            compose.runOnIdle { theme = value }
            for (card in settingsGuideCards) {
                compose.onNodeWithText(card.title).requestFocus().assertIsFocused()
                val label = compose.onNodeWithText(card.title, useUnmergedTree = true)
                label.assertIsDisplayed()
                val layout = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                val foreground = layout.single().layoutInput.style.color
                assertTrue("${value.name}/${card.title}: $foreground on $background",
                    contrastRatio(foreground, background) >= 4.5f)
                assertFalse(layout.single().hasVisualOverflow)
            }
        }
        compose.onNodeWithText("一般").requestFocus().performKeyInput {
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        compose.onNodeWithText("再生").assertIsFocused()
    }
}
