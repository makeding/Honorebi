package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.player.PreparedPlayerPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class ChannelListOverlayTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun preparationFollowsForegroundDrawAndDoesNotStealFocus_thenReusesPanel() {
        val visible = mutableStateOf(false)
        val prepare = mutableStateOf(false)
        var foregroundDrawn = false
        var created = 0
        var disposed = 0
        var initialFocus = 0
        var lastActive = false
        var panelDraws = 0
        val channels = listOf(Channel(
            id = "gr1", displayChannelId = "gr011", name = "Prepared Channel",
            channelNumber = "011", type = "GR", isWatchable = true, isDisplay = true,
            programPresent = null, programFollowing = null,
        ))
        composeRule.setContent {
            KomorebiTheme {
                val foregroundFocus = remember { FocusRequester() }
                val panelFocus = remember { FocusRequester() }
                val inputModeManager = LocalInputModeManager.current
                LaunchedEffect(Unit) {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    foregroundFocus.requestFocus()
                }
                Box(Modifier.size(960.dp, 540.dp)) {
                    Box(Modifier.fillMaxSize().testTag("foreground")
                        .focusRequester(foregroundFocus).focusable()
                        .drawWithContent { drawContent(); foregroundDrawn = true })
                    PreparedPlayerPanel(visible.value, prepare.value, modifier = Modifier.fillMaxSize()) { active ->
                        lastActive = active
                        DisposableEffect(Unit) {
                            assertEquals(true, foregroundDrawn)
                            created++
                            onDispose { disposed++ }
                        }
                        ChannelListOverlay(
                            groupedChannels = mapOf("GR" to channels), recentChannels = emptyList(),
                            recentRecordings = emptyList(), backendType = "", konomiIp = "", konomiPort = "",
                            currentChannelId = "gr1", onChannelSelect = {}, onRecordingSelect = {},
                            logoUrls = emptyMap(), shouldCropLogo = false, focusRequester = panelFocus,
                            onFirstDraw = { panelDraws++ }, onInitialFocus = { initialFocus++ }, isActive = active,
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { prepare.value = true }
        composeRule.onRoot().captureToImage()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertEquals(0, initialFocus)
        }
        composeRule.onNodeWithTag("foreground").assertIsFocused()
        composeRule.onNodeWithText("Prepared Channel").assertIsNotDisplayed().assertIsNotFocused()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onRoot().captureToImage()
        composeRule.mainClock.advanceTimeBy(400)
        try {
            composeRule.onNodeWithText("Prepared Channel").assertIsDisplayed().assertIsFocused()
        } catch (error: AssertionError) {
            throw AssertionError("active=$lastActive draws=$panelDraws focus=$initialFocus logs=${ShadowLog.getLogsForTag("ChannelBrowserFocus")}", error)
        }
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertEquals(1, initialFocus)
            visible.value = false
            prepare.value = false
        }
        composeRule.runOnIdle { assertEquals(created, disposed) }
    }

    @Test
    fun openingAtLaterChannel_focusesItAndKeepsGeometryAcrossCatalogRefresh() {
        val channels = mutableStateOf((0..24).map { index ->
            Channel(
                id = "gr$index", displayChannelId = "GR$index", name = "Channel $index",
                channelNumber = "$index", type = "GR", isWatchable = true, isDisplay = true,
                programPresent = null, programFollowing = null,
            )
        })
        var selected: String? = null
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    ChannelListOverlay(
                        groupedChannels = mapOf("GR" to channels.value), recentChannels = emptyList(),
                        recentRecordings = emptyList(), backendType = "", konomiIp = "", konomiPort = "",
                        currentChannelId = "gr20", onChannelSelect = { selected = it.id },
                        onRecordingSelect = {}, logoUrls = emptyMap(), shouldCropLogo = false,
                        focusRequester = remember { FocusRequester() },
                    )
                }
            }
        }
        // Robolectric's first focus attempt can precede lazy-item attachment.
        composeRule.mainClock.advanceTimeBy(400)
        val target = composeRule.onNodeWithText("Channel 20")
        target.assertIsDisplayed().assertIsFocused()
        val bounds = target.getUnclippedBoundsInRoot()
        composeRule.runOnIdle { channels.value = channels.value.map { it.copy() } }
        target.assertIsDisplayed().assertIsFocused()
        assertEquals(bounds, target.getUnclippedBoundsInRoot())
        target.performClick()
        composeRule.runOnIdle { assertEquals("gr20", selected) }
    }
}
