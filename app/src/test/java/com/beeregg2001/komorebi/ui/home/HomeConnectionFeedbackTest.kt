package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }

        val retainedContent = compose.onNodeWithTag("retained-content").requestFocus().assertIsFocused()
        val initialBounds = compose.onNodeWithTag(HOME_CONNECTION_FEEDBACK_SLOT_TAG)
            .getUnclippedBoundsInRoot()
        compose.onNodeWithText("接続設定").assertDoesNotExist()

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
    fun failureAndRecoveryAreInformationalOnlyWithoutButtonsOrFocus() {
        val state = mutableStateOf(HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline))
        compose.setContent {
            KomorebiTheme {
                HomeConnectionFeedback(state = state.value)
            }
        }
        listOf(
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.BackendFailure, "HTTP 503"),
            HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Recovering, "HTTP 503"),
        ).forEach { next ->
            compose.runOnIdle { state.value = next }
            compose.onNodeWithText("接続設定").assertDoesNotExist()
            compose.onNodeWithText("再試行").assertDoesNotExist()
            compose.onNodeWithText("詳細").assertDoesNotExist()
            if (next.reason != null) compose.onNodeWithText(next.reason).assertExists()
        }
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
