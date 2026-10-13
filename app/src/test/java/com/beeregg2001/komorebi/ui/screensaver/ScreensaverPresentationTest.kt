package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

    @Test fun networkStatesAreIconOnlyAndKeepNeighborBounds() {
        val transport = mutableStateOf(NetworkTransport.WIFI)
        compose.setContent {
            KomorebiTheme {
                Row {
                    ScreensaverNetworkIcon(transport.value)
                    Box(Modifier.size(48.dp).testTag("neighbor"))
                }
            }
        }
        val iconBounds = compose.onNodeWithTag("screensaver-network").fetchSemanticsNode().boundsInRoot
        val neighborBounds = compose.onNodeWithTag("neighbor").fetchSemanticsNode().boundsInRoot
        for ((state, description) in listOf(
            NetworkTransport.WIFI to "Wi-Fi",
            NetworkTransport.ETHERNET to "有線LAN",
            NetworkTransport.OTHER to "ネットワーク接続中",
            NetworkTransport.DISCONNECTED to "ネットワーク未接続",
        )) {
            compose.runOnIdle { transport.value = state }
            compose.onNodeWithContentDescription(description).assertExists()
            compose.onNodeWithText(description).assertDoesNotExist()
            assertEquals(iconBounds, compose.onNodeWithTag("screensaver-network").fetchSemanticsNode().boundsInRoot)
            assertEquals(neighborBounds, compose.onNodeWithTag("neighbor").fetchSemanticsNode().boundsInRoot)
        }
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
