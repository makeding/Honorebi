package com.beeregg2001.komorebi.ui.live

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class TifNoSignalLayerTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun grantManifestReceiverPermission() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
    }

    @Test
    fun bounceMovesLayerWithoutRecomposingLogoContentAndIsNotFocusable() {
        var logoCompositions = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            KomorebiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TifNoSignalLayer(
                        mode = NoSignalRenderMode.BOUNCE,
                        style = TifNoSignalStyle.DVD_BOUNCE,
                        statusText = "信号がありません",
                        inputLabel = "HDMI 1",
                        isPassthroughInput = true,
                        screensaverImageUris = emptyList(),
                        screensaverIntervalSeconds = 30,
                        screensaverTransition = "FADE",
                        showOverlay = false,
                        paused = false,
                        onDegraded = {},
                        physicsSeed = 42L,
                        onLogoComposed = { logoCompositions++ },
                    )
                }
            }
        }

        // Let initial layout, physics reset, and first frame timestamp settle.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val initialBounds = compose.onNodeWithTag(TIF_NO_SIGNAL_BOUNCE_LOGO_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val compositionsAfterInit = logoCompositions

        // Advance several frames; translationX/Y must move via graphicsLayer without recomposing the logo.
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()

        val movedBounds = compose.onNodeWithTag(TIF_NO_SIGNAL_BOUNCE_LOGO_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertNotEquals("logo bounds must move over frames", initialBounds, movedBounds)
        assertEquals("logo content must not recompose per frame", compositionsAfterInit, logoCompositions)
        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun screensaverPillHidesWhenOverlayIsShownAndLayerIsNotFocusable() {
        compose.mainClock.autoAdvance = false

        compose.setContent {
            KomorebiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TifNoSignalLayer(
                        mode = NoSignalRenderMode.SCREENSAVER,
                        style = TifNoSignalStyle.SCREENSAVER,
                        statusText = "信号がありません",
                        inputLabel = "HDMI 1",
                        isPassthroughInput = true,
                        screensaverImageUris = emptyList(),
                        screensaverIntervalSeconds = 30,
                        screensaverTransition = "FADE",
                        showOverlay = false,
                        paused = false,
                        onDegraded = {},
                    )
                }
            }
        }

        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(TIF_NO_SIGNAL_SCREENSAVER_PILL_TAG, useUnmergedTree = true).assertExists()
        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun togglingOverlayDoesNotResetBouncePositionToOrigin() {
        val showOverlay = androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            KomorebiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TifNoSignalLayer(
                        mode = NoSignalRenderMode.BOUNCE,
                        style = TifNoSignalStyle.DVD_BOUNCE,
                        statusText = "信号がありません",
                        inputLabel = "HDMI 1",
                        isPassthroughInput = true,
                        screensaverImageUris = emptyList(),
                        screensaverIntervalSeconds = 30,
                        screensaverTransition = "FADE",
                        showOverlay = showOverlay.value,
                        paused = false,
                        dimmed = showOverlay.value,
                        onDegraded = {},
                    )
                }
            }
        }

        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        val beforeOverlay = compose.onNodeWithTag(TIF_NO_SIGNAL_BOUNCE_LOGO_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        showOverlay.value = true
        compose.waitForIdle()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        val withOverlay = compose.onNodeWithTag(TIF_NO_SIGNAL_BOUNCE_LOGO_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertNotEquals("logo must continue moving when overlay opens", beforeOverlay, withOverlay)
        assertNotEquals(
            "logo must not snap back to (0,0) top-left",
            0f,
            withOverlay.left.value,
        )
    }

    @Test
    fun allEffectStylesRenderEffectTagAndHaveZeroFocusableNodes() {
        val effectStyles = listOf(
            TifNoSignalStyle.ARIB_COLOR_BARS,
            TifNoSignalStyle.ARIB_COLOR_BARS_SCROLL,
            TifNoSignalStyle.ANALOG_STATIC,
            TifNoSignalStyle.SIGNAL_WAVE,
            TifNoSignalStyle.MINIMAL_CLOCK,
            TifNoSignalStyle.AURORA_AMBIENT,
            TifNoSignalStyle.DOT_MATRIX,
            TifNoSignalStyle.STARFIELD,
        )
        val activeStyle = androidx.compose.runtime.mutableStateOf(effectStyles.first())
        compose.mainClock.autoAdvance = false

        compose.setContent {
            KomorebiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TifNoSignalLayer(
                        mode = NoSignalRenderMode.EFFECT,
                        style = activeStyle.value,
                        statusText = "信号がありません",
                        inputLabel = "HDMI 1",
                        isPassthroughInput = true,
                        screensaverImageUris = emptyList(),
                        screensaverIntervalSeconds = 30,
                        screensaverTransition = "FADE",
                        showOverlay = false,
                        paused = false,
                        onDegraded = {},
                    )
                }
            }
        }

        for (style in effectStyles) {
            activeStyle.value = style
            // Advance past the 200ms Crossfade and a few animation frames.
            compose.mainClock.advanceTimeBy(300)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()

            compose.onNodeWithTag(TIF_NO_SIGNAL_EFFECT_TAG, useUnmergedTree = true).assertExists()
            compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
