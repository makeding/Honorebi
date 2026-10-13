package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class ScreensaverPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dateUsesJapaneseEvenOnChineseDevice() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.TRADITIONAL_CHINESE)
            assertEquals("2026年10月13日 火曜日", screensaverDate(LocalDateTime.of(2026, 10, 13, 19, 20)))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun statusIsHorizontalTopRightAndUpcomingRemainsOptional() {
        compose.setContent {
            KomorebiTheme {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    ScreensaverContent(emptyList(), 30, "NONE")
                }
            }
        }
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("screensaver-status").fetchSemanticsNode().boundsInRoot
        assertTrue(status.left > screen.width / 2)
        assertTrue(status.bottom < screen.height / 3)
        compose.onNodeWithTag("screensaver-upcoming-panel").assertDoesNotExist()
    }
}
