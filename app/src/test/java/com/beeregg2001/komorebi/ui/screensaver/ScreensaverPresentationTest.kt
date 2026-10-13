package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import org.robolectric.annotation.GraphicsMode
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun readabilityFadesFromCornersWithoutInteriorHotspots() {
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(960, 540)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(bitmap),
            androidx.compose.ui.geometry.Size(960f, 540f),
        ) {
            drawRect(Color.White)
            drawScreensaverReadability()
        }
        val pixels = bitmap.toPixelMap()
        fun brightness(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].red
        assertTrue(brightness(0.02f, 0.98f) < 0.55f)
        assertTrue(brightness(0.98f, 0.02f) < 0.6f)
        // Contrast comes from the corner; it decreases toward the artwork without an interior blob.
        assertTrue(brightness(0.02f, 0.98f) < brightness(0.14f, 0.85f))
        assertTrue(brightness(0.14f, 0.85f) < brightness(0.3f, 0.7f))
        assertTrue(brightness(0.98f, 0.02f) < brightness(0.86f, 0.12f))
        assertTrue(brightness(0.86f, 0.12f) < brightness(0.7f, 0.3f))
        assertTrue(brightness(0.5f, 0.5f) > 0.99f)

    }

}
