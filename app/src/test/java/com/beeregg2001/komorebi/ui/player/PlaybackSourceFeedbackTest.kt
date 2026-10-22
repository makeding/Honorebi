package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class PlaybackSourceFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureRetryAndRepeatedFailureKeepBoundsAndFocusedAction() {
        val failure = UserFacingFailure("サーバーと通信できません。再試行してください。", "SOURCE_NETWORK")
        val state = mutableStateOf<PlaybackSourceRequest>(PlaybackSourceRequest.Pending())
        var retries = 0
        compose.setContent {
            KomorebiTheme {
                PlaybackSourceFeedback(state.value, onRetry = {
                    retries++
                    state.value = PlaybackSourceRequest.Pending(failure)
                })
            }
        }
        compose.onNodeWithTag("playback-loading-notice").assertIsDisplayed()
        compose.onNodeWithTag("playback-source-feedback").assertDoesNotExist()
        compose.runOnIdle { state.value = PlaybackSourceRequest.Failed(failure) }
        compose.onNodeWithTag("playback-loading-notice").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("playback-source-feedback").getUnclippedBoundsInRoot()
        val retry = compose.onNodeWithTag("playback-source-retry")
        retry.assertIsFocused()
        compose.onNodeWithText("${failure.message}（${failure.code}）").assertIsDisplayed()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        retry.assertIsFocused().assertIsNotEnabled()
        assertEquals(bounds, compose.onNodeWithTag("playback-source-feedback").getUnclippedBoundsInRoot())
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries); state.value = PlaybackSourceRequest.Failed(failure) }
        retry.assertIsFocused().assertIsEnabled()
        assertEquals(bounds, compose.onNodeWithTag("playback-source-feedback").getUnclippedBoundsInRoot())
        compose.runOnIdle { state.value = PlaybackSourceRequest.Ready }
        compose.onNodeWithTag("playback-source-feedback").assertDoesNotExist()
    }

    @Test fun initialPendingUsesPassiveClockLaneWithoutRetryButtons() {
        compose.setContent {
            KomorebiTheme {
                PlaybackSourceFeedback(
                    request = PlaybackSourceRequest.Pending(),
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("playback-source-retry").assertDoesNotExist()
        compose.onNodeWithTag("playback-source-back").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("playback-loading-notice").getUnclippedBoundsInRoot()
        assertEquals(24f, bounds.left.value)
        assertEquals(96f, bounds.top.value)
    }
}
