package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class HomeConnectionFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun stateTransitionsKeepTheFixedSlotAndExistingContentFocus() {
        val state = mutableStateOf(HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Hidden))
        compose.setContent {
            KomorebiTheme {
                Box(Modifier.fillMaxSize()) {
                    Button(
                        onClick = {},
                        modifier = Modifier.align(Alignment.Center).testTag("retained-content"),
                    ) { Text("番組") }
                    HomeConnectionFeedback(
                        state = state.value,
                        onOpenConnectionSettings = {},
                        onRecoveryFocus = {},
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }

        val retainedContent = compose.onNodeWithTag("retained-content").requestFocus().assertIsFocused()
        val initialBounds = compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_SLOT_TAG)
            .getUnclippedBoundsInRoot()
        compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_ACTION_TAG).assertDoesNotExist()

        listOf(
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.BackendFailure, "[BACKEND_HTTP_503 / HTTP 503]"),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Recovering),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Hidden),
        ).forEach { next ->
            compose.runOnIdle { state.value = next }
            assertEquals(
                initialBounds,
                compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_SLOT_TAG).getUnclippedBoundsInRoot(),
            )
            retainedContent.assertIsFocused()
        }
    }

    @Test
    fun failureAndRecoveryKeepInlineReasonAndSettingsWithoutDetailsOrRefresh() {
        val state = mutableStateOf(HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline))
        var settingsOpens = 0
        compose.setContent {
            KomorebiTheme {
                HomeConnectionFeedback(
                    state = state.value,
                    onOpenConnectionSettings = { settingsOpens++ },
                    onRecoveryFocus = {},
                )
            }
        }
        val action = compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_ACTION_TAG)
        val bounds = action.getUnclippedBoundsInRoot()
        listOf(
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.BackendFailure, "HTTP 503"),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Recovering, "HTTP 503"),
        ).forEach { next ->
            compose.runOnIdle { state.value = next }
            action.assertTextContains("接続設定").requestFocus()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            assertEquals(bounds, action.getUnclippedBoundsInRoot())
            compose.onNodeWithText("詳細").assertDoesNotExist()
            compose.onNodeWithText("再試行").assertDoesNotExist()
            if (next.reason != null) compose.onNodeWithText(next.reason).assertExists()
        }
        assertEquals(3, settingsOpens)
    }

    @Test
    fun focusedSettingsReturnsToContentAfterSuccessfulRecovery() {
        val state = mutableStateOf(
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.BackendFailure, "HTTP 503"),
        )
        val retainedContentRequester = FocusRequester()
        compose.setContent {
            KomorebiTheme {
                Box(Modifier.fillMaxSize()) {
                    Button(
                        onClick = {},
                        modifier = Modifier.align(Alignment.Center)
                            .focusRequester(retainedContentRequester).testTag("recovery-content"),
                    ) { Text("番組") }
                    HomeConnectionFeedback(
                        state = state.value,
                        onOpenConnectionSettings = {},
                        onRecoveryFocus = { retainedContentRequester.requestFocus() },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_ACTION_TAG).requestFocus().assertIsFocused()
        compose.runOnIdle { state.value = HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Hidden) }
        compose.onNodeWithTag("recovery-content").assertIsFocused()
    }

    @Test
    fun policyDoesNotExposeOrdinaryConnectedWorkAsRecovery() {
        assertEquals(
            HomeConnectionFeedbackKind.Hidden,
            homeConnectionFeedbackState(
                networkAvailable = true,
                hasBackendFailure = false,
                backendFailureReason = null,
                recoveryInProgress = false,
            ).kind,
        )
        assertEquals(
            HomeConnectionFeedbackKind.Recovering,
            homeConnectionFeedbackState(
                networkAvailable = true,
                hasBackendFailure = true,
                backendFailureReason = "前回の接続に失敗しました。",
                recoveryInProgress = true,
            ).kind,
        )
        assertEquals(
            HomeConnectionFeedbackKind.DeviceOffline,
            homeConnectionFeedbackState(
                networkAvailable = false,
                hasBackendFailure = true,
                backendFailureReason = "古いサーバーエラー",
                recoveryInProgress = true,
            ).kind,
        )
    }
}
