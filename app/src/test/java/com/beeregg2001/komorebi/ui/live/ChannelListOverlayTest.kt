package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class ChannelListOverlayTest {
    @get:Rule val composeRule = createComposeRule()

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
