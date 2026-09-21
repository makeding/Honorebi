package com.beeregg2001.komorebi.ui.live

import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class LiveDualPlayerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failedRightSlotRetainsGeometryShowsReasonAndOnlyRetriesRight() {
        val channel = Channel("iptv", "iptv", "CCTV", "--", type = "IPTV", isWatchable = true,
            isDisplay = true, programPresent = null, programFollowing = null)
        val state = LivePlayerState(RuntimeEnvironment.getApplication()).apply {
            dualRightChannel = channel
            isDualDisplayMode = true
            activeDualPlayerIndex = 1
        }
        var mainRetries = 0
        var dualRetries = 0
        compose.setContent {
            KomorebiTheme {
                DualDisplayPlayer(state, channel, { "" }, false, false, false,
                    null, 0, 0, 1f, null, null, false,
                    null, 0, 0, 1f, null, null, false, false, null,
                    { mainRetries++ }, { dualRetries++ })
            }
        }
        val left = compose.onNodeWithTag("live-main-slot").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("live-dual-slot").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            state.dualSseStatus = "Error"
            state.dualSseDetail = "ネットテレビに接続できません (LIVE_HTTP_503)"
        }
        compose.onNodeWithText("ネットテレビに接続できません (LIVE_HTTP_503)").assertIsDisplayed()
        compose.onNodeWithText("再試行").assertIsDisplayed().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(0, mainRetries)
        assertEquals(1, dualRetries)
        assertEquals(left, compose.onNodeWithTag("live-main-slot").getUnclippedBoundsInRoot())
        assertEquals(right, compose.onNodeWithTag("live-dual-slot").getUnclippedBoundsInRoot())
        compose.onAllNodesWithText("IPTV", substring = true).assertCountEquals(0)
    }
}
