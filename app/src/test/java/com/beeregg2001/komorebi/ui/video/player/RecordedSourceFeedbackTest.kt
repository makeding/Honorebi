package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class RecordedSourceFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureRetryAndRepeatedFailureKeepBoundsAndFocusedAction() {
        val failure = UserFacingFailure("サーバーと通信できません。再試行してください。", "RECORDED_SOURCE_NETWORK")
        val state = mutableStateOf<RecordedSourceRequest>(RecordedSourceRequest.Pending())
        var retries = 0
        compose.setContent {
            KomorebiTheme {
                RecordedSourceFeedback(state.value, onRetry = {
                    retries++
                    state.value = RecordedSourceRequest.Pending(failure)
                })
            }
        }
        val bounds = compose.onNodeWithTag("recorded-source-feedback").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("recorded-source-retry").assertIsNotEnabled()
        compose.runOnIdle { state.value = RecordedSourceRequest.Failed(failure) }
        val retry = compose.onNodeWithTag("recorded-source-retry")
        retry.assertIsFocused()
        compose.onNodeWithText("${failure.message}（${failure.code}）").assertIsDisplayed()
        retry.performClick()
        retry.assertIsFocused().assertIsNotEnabled()
        assertEquals(bounds, compose.onNodeWithTag("recorded-source-feedback").getUnclippedBoundsInRoot())
        // Directly trigger the click callback too: pending requests must be guarded in code.
        retry.performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = RecordedSourceRequest.Failed(failure) }
        retry.assertIsFocused().assertIsEnabled()
        assertEquals(bounds, compose.onNodeWithTag("recorded-source-feedback").getUnclippedBoundsInRoot())
        compose.runOnIdle { state.value = RecordedSourceRequest.Ready }
        compose.onNodeWithTag("recorded-source-feedback").assertDoesNotExist()
    }
}
